"""Entrega de boletos PDF ao e-mail confirmado no cadastro IXC.

O terminal nunca informa destinatário, corpo nem anexo: tudo vem do IXC.
Não deve ser confundido com o envio interno do IXC; utiliza SMTP da ISM.
"""
import base64
import binascii
import os
import re
import smtplib
import sqlite3
import ssl
import time
from email.message import EmailMessage
from threading import Lock

_LOCK = Lock()
_EMAIL_RE = re.compile(r"^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)+$")


class BoletoEmailError(Exception):
    def __init__(self, detail, status=422):
        super().__init__(detail)
        self.status = status


def registered_email(customer):
    """Apenas e-mail único, sem aceitar múltiplos destinos ou caracteres de cabeçalho."""
    if not isinstance(customer, dict):
        return ""
    for field in ("email", "email_principal", "email_cobranca"):
        value = str(customer.get(field) or "").strip()
        if value:
            if len(value) <= 254 and _EMAIL_RE.fullmatch(value):
                return value
            return ""
    return ""


def mask_email(value):
    local, host = value.split("@", 1)
    return local[:2] + "***@" + host[:1] + "***" + host[host.rfind("."):]


def _config():
    if os.getenv("BOLETO_EMAIL_ENABLED", "").lower() not in ("1", "true", "sim"):
        raise BoletoEmailError("Envio automático de boletos por e-mail ainda não habilitado", 503)
    host = os.getenv("BOLETO_SMTP_HOST", "").strip()
    user = os.getenv("BOLETO_SMTP_USER", "").strip()
    password = os.getenv("BOLETO_SMTP_PASSWORD", "")
    sender = os.getenv("BOLETO_SMTP_FROM", "").strip()
    try:
        port = int(os.getenv("BOLETO_SMTP_PORT", "465"))
    except ValueError:
        port = -1
    if not host or any(c in host for c in ("/", "@", "\r", "\n", " ")) or len(host) > 255:
        raise BoletoEmailError("SMTP de boletos não configurado", 503)
    if port not in (465, 587) or not user or not password or not _EMAIL_RE.fullmatch(sender):
        raise BoletoEmailError("Credenciais SMTP de boletos incompletas", 503)
    return host, port, user, password, sender


def _connection():
    path = os.getenv("BOLETO_EMAIL_DB", "/data/boleto_email.db")
    if not path.startswith("/") or not path.endswith(".db"):
        raise BoletoEmailError("Auditoria de e-mail não configurada", 503)
    old = os.umask(0o077)
    try:
        conn = sqlite3.connect(path, timeout=10)
    finally:
        os.umask(old)
    try:
        os.chmod(path, 0o600)
        conn.execute("PRAGMA busy_timeout=10000")
        conn.execute("""CREATE TABLE IF NOT EXISTS boleto_send_attempts (
            invoice_id TEXT PRIMARY KEY,
            state TEXT NOT NULL,
            updated INTEGER NOT NULL
        )""")
        conn.commit()
        return conn
    except Exception:
        conn.close()
        raise


def send_pdf(invoice_id, email, pdf_base64):
    """Uma solicitação por título a cada 5 min, mesmo após timeout SMTP incerto."""
    config = _config()
    if not re.fullmatch(r"[0-9]{1,15}", str(invoice_id)):
        raise BoletoEmailError("Cobrança inválida")
    if not _EMAIL_RE.fullmatch(email):
        raise BoletoEmailError("E-mail inválido no cadastro IXC")
    try:
        pdf = base64.b64decode(pdf_base64, validate=True)
    except (ValueError, binascii.Error) as exc:
        raise BoletoEmailError("PDF indisponível no IXC", 422) from exc
    if not pdf.startswith(b"%PDF-") or len(pdf) > 8 * 1024 * 1024:
        raise BoletoEmailError("PDF indisponível no IXC", 422)

    now = int(time.time())
    with _LOCK:
        conn = _connection()
        try:
            with conn:
                conn.execute("BEGIN IMMEDIATE")
                row = conn.execute(
                    "SELECT state,updated FROM boleto_send_attempts WHERE invoice_id=?",
                    (str(invoice_id),),
                ).fetchone()
                if row and now - row[1] < 300:
                    raise BoletoEmailError(
                        "Envio recente ou pendente para esta fatura. "
                        "Confira a caixa de e-mail antes de solicitar novamente.", 409)
                conn.execute(
                    "INSERT INTO boleto_send_attempts(invoice_id,state,updated) VALUES(?,?,?) "
                    "ON CONFLICT(invoice_id) DO UPDATE SET state=excluded.state,updated=excluded.updated",
                    (str(invoice_id), "enviando", now),
                )
        finally:
            conn.close()

    host, port, user, password, sender = config
    message = EmailMessage()
    message["Subject"] = "ISM Telecom - Boleto da fatura " + str(invoice_id)
    message["From"] = sender
    message["To"] = email
    message.set_content(
        "Segue em anexo o boleto solicitado para a fatura "
        + str(invoice_id) + ".\n"
        "Confira o beneficiário e o valor antes de efetuar o pagamento.\n\n"
        "ISM Telecom"
    )
    message.add_attachment(pdf, maintype="application", subtype="pdf",
                           filename="ISM_Fatura_" + str(invoice_id) + ".pdf")
    state = "conferir"
    try:
        context = ssl.create_default_context()
        if port == 465:
            with smtplib.SMTP_SSL(host, port, timeout=15, context=context) as smtp:
                smtp.login(user, password)
                refused = smtp.send_message(message)
        else:
            with smtplib.SMTP(host, port, timeout=15) as smtp:
                smtp.ehlo()
                smtp.starttls(context=context)
                smtp.ehlo()
                smtp.login(user, password)
                refused = smtp.send_message(message)
        if refused:
            raise BoletoEmailError("Servidor SMTP não aceitou o destinatário", 502)
        state = "aceito"
        return {"enviado": True, "situacao": "aceito_pelo_smtp",
                "destinatario_mascarado": mask_email(email)}
    except (smtplib.SMTPException, OSError, TimeoutError) as exc:
        raise BoletoEmailError(
            "Não foi possível confirmar o envio SMTP. "
            "Confira o log do servidor antes de tentar novamente.", 502) from exc
    finally:
        conn = _connection()
        try:
            with conn:
                conn.execute("UPDATE boleto_send_attempts SET state=?,updated=? WHERE invoice_id=?",
                             (state, int(time.time()), str(invoice_id)))
        finally:
            conn.close()


def reserve_ixc_send(invoice_id):
    """Evita envio duplicado pela API IXC por 5 min, inclusive após timeout incerto.

    Usa DB já persistido em /data, mas NÃO requer configuração SMTP do aplicativo.
    """
    ident = str(invoice_id)
    if not re.fullmatch(r"[1-9][0-9]{0,14}", ident):
        raise BoletoEmailError("Cobrança inválida")
    now = int(time.time())
    with _LOCK:
        conn = _connection()
        try:
            with conn:
                conn.execute("BEGIN IMMEDIATE")
                row = conn.execute(
                    "SELECT state,updated FROM boleto_send_attempts WHERE invoice_id=?",
                    (ident,),
                ).fetchone()
                if row and now - row[1] < 300:
                    raise BoletoEmailError(
                        "Solicitação recente desta fatura. Confira o e-mail ou log do IXC "
                        "antes de tentar novamente.", 409
                    )
                conn.execute(
                    "INSERT INTO boleto_send_attempts(invoice_id,state,updated) VALUES(?,?,?) "
                    "ON CONFLICT(invoice_id) DO UPDATE SET state=excluded.state,updated=excluded.updated",
                    (ident, "solicitado_ixc", now),
                )
        finally:
            conn.close()
