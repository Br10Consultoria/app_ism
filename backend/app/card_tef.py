"""Transações m-SiTef em homologação supervisionada.

NÃO concede baixa IXC e NÃO confia em retorno do terminal como liquidação.
Uma tentativa existente bloqueia novas tentativas para a mesma fatura até revisão
administrativa. Nunca registra PIN/PAN nem comprovante TEF bruto.
"""
import hashlib
import hmac
import os
import re
import secrets
import sqlite3
import time
import uuid
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP


class CardTefError(Exception):
    def __init__(self, message, status=422):
        super().__init__(message)
        self.status = status


def amount_in_cents(value):
    raw = str(value or "").strip()
    # Formatos do IXC: 150.00 / 150,00 / 1.500,00
    if re.fullmatch(r"[0-9]{1,9}(?:\.[0-9]{3})*,[0-9]{1,2}", raw):
        raw = raw.replace(".", "").replace(",", ".")
    elif re.fullmatch(r"[0-9]{1,9},[0-9]{1,2}", raw):
        raw = raw.replace(",", ".")
    elif not re.fullmatch(r"[0-9]{1,9}(?:\.[0-9]{1,2})?", raw):
        raise CardTefError("Valor de cobrança IXC inválido")
    try:
        decimal = Decimal(raw)
    except InvalidOperation as e:
        raise CardTefError("Valor de cobrança inválido") from e
    if decimal <= 0 or decimal > Decimal("50000.00"):
        raise CardTefError("Valor fora do limite de homologação")
    cents = decimal * 100
    if cents != cents.to_integral_value(rounding=ROUND_HALF_UP):
        raise CardTefError("Valor deve ter até dois decimais")
    return int(cents)


def homologation_config():
    """Apenas SitDemo local/rede privada: nunca conectar ambiente de produção."""
    if os.getenv("CARD_TEF_HOMOLOGATION_ENABLED", "").strip().lower() not in ("true", "1", "sim"):
        raise CardTefError("TEF desativado: habilite apenas na homologação autorizada", 503)
    if os.getenv("CARD_TEF_MODE", "").strip().lower() != "sitdemo":
        raise CardTefError("Esta versão só aceita CARD_TEF_MODE=sitdemo", 503)
    company = os.getenv("MSITEF_EMPRESA", "").strip()
    addr = os.getenv("MSITEF_ENDERECO", "").strip()
    mode = os.getenv("MSITEF_COM_EXTERNA", "").strip()
    cnpj = re.sub(r"\\D", "", os.getenv("MSITEF_CNPJ_ESTABELECIMENTO", ""))
    automation = re.sub(r"\\D", "", os.getenv("MSITEF_CNPJ_AUTOMACAO", ""))
    if company != "00000000" or mode != "0":
        raise CardTefError("SitDemo exige empresaSitef=00000000 e comExterna=0", 503)
    if len(cnpj) != 14 or len(automation) != 14:
        raise CardTefError("Informe CNPJ estabelecimento e automação (14 dígitos)", 503)
    if not addr or len(addr) > 240 or not re.fullmatch(r"[a-zA-Z0-9.\\-_:;]+", addr):
        raise CardTefError("Configure MSITEF_ENDERECO com IP/host acessível pelo terminal", 503)
    if addr.startswith(("127.", "0.0.0.0", "localhost")):
        raise CardTefError("SitDemo deve ser acessível pela rede da maquineta", 503)
    # O número lógico Getnet da produção não é o terminal de demonstração.
    result = {"empresaSitef": company, "enderecoSitef": addr,
              "CNPJ_CPF": cnpj, "cnpj_automacao": automation,
              "comExterna": mode, "timeoutColeta": "60",
              "acessibilidadeVisual": "0"}
    terminal = os.getenv("MSITEF_TERMINAL", "").strip()
    if terminal:
        if not re.fullmatch(r"[A-Za-z0-9_-]{1,30}", terminal):
            raise CardTefError("Terminal SitDemo inválido", 503)
        result["terminalSitef"] = terminal
    return result


def _db():
    path = os.getenv("CARD_TEF_DB", "/data/tef_attempts.db")
    if not path.startswith("/") or not path.endswith(".db"):
        raise CardTefError("Caminho de auditoria TEF inválido", 503)
    old = os.umask(0o077)
    try:
        con = sqlite3.connect(path, timeout=15)
    finally:
        os.umask(old)
    try:
        os.chmod(path, 0o600)
        con.execute("PRAGMA busy_timeout=15000")
        con.execute("""
            CREATE TABLE IF NOT EXISTS tef_attempts(
                id TEXT PRIMARY KEY,
                invoice_id TEXT NOT NULL,
                customer_id TEXT NOT NULL,
                method TEXT NOT NULL,
                cents INTEGER NOT NULL,
                token_hash TEXT NOT NULL,
                state TEXT NOT NULL,
                created INTEGER NOT NULL,
                updated INTEGER NOT NULL,
                codresp TEXT NOT NULL DEFAULT '',
                nsu TEXT NOT NULL DEFAULT '',
                autorizacao TEXT NOT NULL DEFAULT '',
                operadora TEXT NOT NULL DEFAULT '',
                retorno_atividade INTEGER NOT NULL DEFAULT -1
            )
        """)
        con.execute("CREATE INDEX IF NOT EXISTS idx_tef_invoice ON tef_attempts(invoice_id, created)")
        con.commit()
        return con
    except Exception:
        con.close()
        raise


def prepare(invoice_id, customer_id, method, cents):
    if method not in ("debito", "credito"):
        raise CardTefError("Apenas débito ou crédito à vista", 422)
    now = int(time.time())
    con = _db()
    try:
        with con:
            con.execute("BEGIN IMMEDIATE")
            existing = con.execute(
                "SELECT id, state FROM tef_attempts WHERE invoice_id=? "
                "ORDER BY created DESC LIMIT 1", (str(invoice_id),)
            ).fetchone()
            if existing:
                # O terminal pode morrer após autorizar. Nunca gerar outra tentativa sem conciliação.
                raise CardTefError(
                    "Já existe tentativa TEF para esta cobrança ("
                    + existing[1] + "). Consulte a Getnet/SiTef e concilie antes de cobrar novamente.",
                    409,
                )
            attempt_id = uuid.uuid4().hex[:20].upper()
            token = secrets.token_urlsafe(32)
            con.execute(
                "INSERT INTO tef_attempts(id,invoice_id,customer_id,method,cents,token_hash,"
                "state,created,updated) VALUES(?,?,?,?,?,?,'iniciada',?,?)",
                (attempt_id, str(invoice_id), str(customer_id), method, cents,
                 hashlib.sha256(token.encode()).hexdigest(), now, now)
            )
            return {"tentativa": attempt_id, "segredo_retorno": token}
    finally:
        con.close()


def submit_result(attempt_id, token, result_code, codresp="", nsu="", authorization="", network=""):
    if not re.fullmatch(r"[A-Z0-9]{20}", str(attempt_id or "")):
        raise CardTefError("Identificador de tentativa inválido")
    if len(str(token or "")) > 300:
        raise CardTefError("Token inválido")
    con = _db()
    try:
        with con:
            con.execute("BEGIN IMMEDIATE")
            row = con.execute(
                "SELECT token_hash,state FROM tef_attempts WHERE id=?", (attempt_id,)
            ).fetchone()
            if not row or not hmac.compare_digest(
                row[0], hashlib.sha256(str(token).encode()).hexdigest()
            ):
                raise CardTefError("Tentativa TEF não encontrada", 404)
            if row[1] != "iniciada":
                raise CardTefError("Tentativa já registrada: verificar conciliação", 409)
            code = str(codresp or "")[:20]
            nsu = str(nsu or "")[:40]
            authorization = str(authorization or "")[:40]
            network = str(network or "")[:60]
            # Sinal positivo vindo do aparelho não é prova autenticada com a rede TEF.
            positive = (int(result_code) == -1 and code in ("0", "00")
                        and bool(nsu))
            state = "retorno_positivo_a_conciliar" if positive else "conferir_tef"
            con.execute(
                "UPDATE tef_attempts SET state=?,updated=?,codresp=?,nsu=?,"
                "autorizacao=?,operadora=?,retorno_atividade=? WHERE id=?",
                (state, int(time.time()), code, nsu, authorization,
                 network, int(result_code), attempt_id),
            )
            return {
                "tentativa": attempt_id,
                "situacao": state,
                "baixa_ixc": "nao_executada",
                "mensagem": "Retorno salvo. Confirme na Getnet/SiTef antes de conciliar o IXC.",
            }
    finally:
        con.close()


def pending_summary():
    con = _db()
    try:
        rows = con.execute(
            "SELECT id,invoice_id,method,cents,state,created,nsu FROM tef_attempts "
            "ORDER BY created DESC LIMIT 100"
        ).fetchall()
        for r in rows:
            print("tentativa=", r[0], "fatura=", r[1], "modalidade=", r[2],
                  "valor_centavos=", r[3], "estado=", r[4], "unix=", r[5],
                  "nsu=", r[6])
    finally:
        con.close()


if __name__ == "__main__":
    pending_summary()
