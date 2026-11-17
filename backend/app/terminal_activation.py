"""Ativação por QR ISM com QR compartilhado reutilizável e revogável.

O QR nunca contém a TERMINAL_KEY; o servidor valida um hash persistente.
CLI: python -m app.terminal_activation --shared
Revogar: python -m app.terminal_activation --revoke-shared
QR de uso único legado: --one-time --minutes 5
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import secrets
import sqlite3
import sys
import time


PREFIX = "ISM-ACT1:"
TOKEN_RE = re.compile(r"^[A-Za-z0-9_-]{43}$")


class ActivationError(Exception):
    pass


def db_path():
    path = os.getenv("TERMINAL_ACTIVATION_DB", "/data/terminal_activation.db")
    if not path.startswith("/") or not path.endswith(".db"):
        raise ActivationError("TERMINAL_ACTIVATION_DB precisa ser um caminho absoluto .db")
    return path


def _connection():
    path = db_path()
    old_mask = os.umask(0o077)
    try:
        conn = sqlite3.connect(path, timeout=10)
    finally:
        os.umask(old_mask)
    try:
        os.chmod(path, 0o600)
        conn.execute("PRAGMA busy_timeout = 10000")
        conn.execute("""CREATE TABLE IF NOT EXISTS terminal_activation (
            code_sha256 TEXT PRIMARY KEY,
            expires_at INTEGER NOT NULL,
            used_at INTEGER
        )""")
        conn.execute("""CREATE TABLE IF NOT EXISTS terminal_shared_activation (
            id INTEGER PRIMARY KEY CHECK(id = 1),
            code_sha256 TEXT NOT NULL,
            created_at INTEGER NOT NULL
        )""")
        # Persistente no mesmo volume /data: não alteramos o QR compartilhado.
        conn.execute("""CREATE TABLE IF NOT EXISTS terminal_registry (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            installation_uuid TEXT UNIQUE NOT NULL,
            created_at INTEGER NOT NULL
        )""")
        conn.commit()
        return conn
    except Exception:
        conn.close()
        raise


def _digest(code: str) -> str:
    return hashlib.sha256(code.encode("ascii")).hexdigest()


def issue(minutes=5):
    """Emite convite SOMENTE para o operador com acesso ao shell do backend."""
    if not 1 <= minutes <= 15:
        raise ActivationError("Validade permitida: de 1 a 15 minutos")
    key = os.getenv("TERMINAL_KEY", "")
    if not 32 <= len(key) <= 512:
        raise ActivationError("TERMINAL_KEY não configurada no backend")
    token = secrets.token_urlsafe(32)
    expires = int(time.time()) + minutes * 60
    conn = _connection()
    try:
        with conn:
            conn.execute("DELETE FROM terminal_activation WHERE expires_at < ?", (int(time.time()) - 3600,))
            conn.execute(
                "INSERT INTO terminal_activation (code_sha256,expires_at,used_at) VALUES (?,?,NULL)",
                (_digest(token), expires),
            )
    finally:
        conn.close()
    return PREFIX + token, expires


def issue_shared():
    """Gera um ÚNICO QR reutilizável, substituindo/revogando o anterior.

    Não há expiração automática. O QR é uma credencial de provisionamento:
    envie apenas a responsáveis autorizados. Guarde a imagem em lugar restrito.
    """
    key = os.getenv("TERMINAL_KEY", "")
    if not 32 <= len(key) <= 512:
        raise ActivationError("TERMINAL_KEY não configurada no backend")
    token = secrets.token_urlsafe(32)
    conn = _connection()
    try:
        with conn:
            conn.execute("BEGIN IMMEDIATE")
            conn.execute(
                "INSERT INTO terminal_shared_activation (id, code_sha256, created_at) "
                "VALUES (1, ?, ?) ON CONFLICT(id) DO UPDATE SET "
                "code_sha256=excluded.code_sha256, created_at=excluded.created_at",
                (_digest(token), int(time.time())),
            )
    finally:
        conn.close()
    return PREFIX + token


def shared_status():
    """Informação administrativa sem expor o token ou seu hash."""
    conn = _connection()
    try:
        row = conn.execute(
            "SELECT created_at FROM terminal_shared_activation WHERE id=1"
        ).fetchone()
        return {"ativo": bool(row), "criado_em": int(row[0]) if row else None}
    finally:
        conn.close()


def revoke_shared():
    """Revoga o QR compartilhado; não desativa dispositivos já provisionados."""
    conn = _connection()
    try:
        with conn:
            conn.execute("BEGIN IMMEDIATE")
            cur = conn.execute("DELETE FROM terminal_shared_activation WHERE id=1")
            return cur.rowcount == 1
    finally:
        conn.close()


INSTALLATION_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)


def _allocate_number(conn, installation_uuid: str) -> str:
    if not isinstance(installation_uuid, str) or not INSTALLATION_RE.fullmatch(installation_uuid):
        raise ActivationError("Identificador da instalação inválido")
    row = conn.execute(
        "SELECT id FROM terminal_registry WHERE installation_uuid=?", (installation_uuid,)
    ).fetchone()
    if row is None:
        conn.execute(
            "INSERT INTO terminal_registry (installation_uuid,created_at) VALUES (?,?)",
            (installation_uuid, int(time.time())),
        )
        row = conn.execute(
            "SELECT id FROM terminal_registry WHERE installation_uuid=?", (installation_uuid,)
        ).fetchone()
    return f"ISM-{int(row[0]):04d}"


def register_device(installation_uuid: str) -> str:
    """Atribui ou recupera o mesmo número para instalação já autenticada.

    Usado na migração de maquinetas ativadas em versões anteriores. O endpoint
    HTTP que invoca esta função exige X-Terminal-Key.
    """
    conn = _connection()
    try:
        with conn:
            conn.execute("BEGIN IMMEDIATE")
            return _allocate_number(conn, installation_uuid)
    finally:
        conn.close()


def redeem_and_register(code: str, installation_uuid: str):
    """Resgata QR e grava ID no MESMO commit. Repetir QR compartilhado é idempotente."""
    if not isinstance(code, str) or not TOKEN_RE.fullmatch(code):
        return None
    if not isinstance(installation_uuid, str) or not INSTALLATION_RE.fullmatch(installation_uuid):
        raise ActivationError("Identificador da instalação inválido")
    now = int(time.time())
    conn = _connection()
    try:
        with conn:
            conn.execute("BEGIN IMMEDIATE")
            cursor = conn.execute(
                "UPDATE terminal_activation SET used_at=? "
                "WHERE code_sha256=? AND used_at IS NULL AND expires_at>=?",
                (now, _digest(code), now),
            )
            if cursor.rowcount != 1:
                shared = conn.execute(
                    "SELECT 1 FROM terminal_shared_activation "
                    "WHERE id=1 AND code_sha256=?",
                    (_digest(code),),
                ).fetchone()
                if shared is None:
                    return None
            return _allocate_number(conn, installation_uuid)
    finally:
        conn.close()


def redeem(code: str):
    """Permite QR compartilhado ilimitado ou consome QR temporário de uso único."""
    if not isinstance(code, str) or not TOKEN_RE.fullmatch(code):
        return False
    now = int(time.time())
    conn = _connection()
    try:
        with conn:
            conn.execute("BEGIN IMMEDIATE")
            cursor = conn.execute(
                "UPDATE terminal_activation SET used_at=? "
                "WHERE code_sha256=? AND used_at IS NULL AND expires_at>=?",
                (now, _digest(code), now),
            )
            if cursor.rowcount == 1:
                return True
            shared = conn.execute(
                "SELECT 1 FROM terminal_shared_activation "
                "WHERE id=1 AND code_sha256=?",
                (_digest(code),),
            ).fetchone()
            return shared is not None
    finally:
        conn.close()


def main():
    parser = argparse.ArgumentParser(
        description="Gerenciamento do QR compartilhado da ISM Telecom. "
                    "Um QR pode ativar todas as maquinetas até a revogação."
    )
    actions = parser.add_mutually_exclusive_group()
    actions.add_argument("--shared", action="store_true",
                         help="Gerar um QR reutilizável e revogar automaticamente o anterior (padrão)")
    actions.add_argument("--one-time", action="store_true",
                         help="Gerar QR antigo de uso único e curta duração")
    actions.add_argument("--revoke-shared", action="store_true",
                         help="Revogar o QR compartilhado ativo")
    actions.add_argument("--status", action="store_true",
                         help="Consultar existência do QR compartilhado (sem expor o código)")
    parser.add_argument("--minutes", type=int, default=None,
                        help="Validade de 1 a 15 minutos, somente com --one-time")
    parser.add_argument(
        "--png", default=None,
        help="Destino do PNG: por padrão /data/ism-ativacao-compartilhada.png; "
             "com --one-time, /tmp/ism-temporario.png",
    )
    parser.add_argument("--no-ascii", action="store_true",
                        help="Não imprimir QR Code no terminal")
    args = parser.parse_args()
    if args.png is None:
        args.png = ("/tmp/ism-temporario.png" if args.one_time
                    else "/data/ism-ativacao-compartilhada.png")

    try:
        if not args.revoke_shared and not args.status:
            if not Path(args.png).is_absolute():
                raise ActivationError("Informe caminho absoluto para --png")
        if args.revoke_shared:
            print("QR compartilhado revogado." if revoke_shared()
                  else "Nenhum QR compartilhado estava ativo.")
            return
        if args.status:
            status = shared_status()
            print("QR compartilhado:", "ATIVO" if status["ativo"] else "INATIVO")
            if status["criado_em"]:
                print("Gerado em:", time.strftime(
                    "%Y-%m-%d %H:%M:%S", time.localtime(status["criado_em"])))
            return

        if args.one_time:
            payload, expiration = issue(5 if args.minutes is None else args.minutes)
            print("ISM Telecom - QR de uso único (opção de emergência)")
            print("Expira em:", time.strftime(
                "%Y-%m-%d %H:%M:%S", time.localtime(expiration)))
        else:
            if args.minutes is not None:
                raise ActivationError("--minutes é exclusivo de --one-time")
            payload = issue_shared()
            print("ISM Telecom - QR COMPARTILHADO para TODAS as maquinetas")
            print("Sem prazo de expiração automática. Ativo até revogação ou troca.")
            print("IMPORTANTE: gerar outro QR --shared invalida o anterior.")
            print("IMPORTANTE: qualquer pessoa com esta imagem pode tentar ativar aparelho.")

        import qrcode
        qr = qrcode.QRCode(
            error_correction=qrcode.constants.ERROR_CORRECT_M,
            box_size=10, border=4
        )
        qr.add_data(payload)
        qr.make(fit=True)
        destination = Path(args.png)
        if not destination.is_absolute():
            raise ActivationError("Informe caminho absoluto para --png")
        qr.make_image(fill_color="black", back_color="white").save(str(destination))
        os.chmod(str(destination), 0o600)
        print("PNG no container:", destination)
        if not args.no_ascii:
            print("\nQR para escanear com a maquineta:\n")
            qr.print_ascii(out=sys.stdout, invert=True)
    except (ActivationError, OSError, sqlite3.Error) as error:
        parser.exit(2, f"Erro na ativação: {error}\n")


if __name__ == "__main__":
    main()
