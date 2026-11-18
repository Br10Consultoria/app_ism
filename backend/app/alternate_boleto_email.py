"""Envio de boleto a outro e-mail com confirmação do destinatário por código.

Não alteramos o cadastro do cliente no IXC e não usamos get_boleto/mail para
um endereço diferente, pois esse endpoint escolhe o e-mail do ERP.
Não expomos documentos antes da prova de acesso ao endereço escolhido.
"""
import hashlib
import hmac
import os
import re
import secrets
import smtplib
import sqlite3
import ssl
import time
from email.message import EmailMessage

from .boleto_email import BoletoEmailError, _EMAIL_RE, _config, send_pdf

CODE_RE = re.compile(r"^[0-9]{6}$")
MAX_AGE = 600
MIN_RESEND = 90
MAX_HOUR = 4
MAX_ATTEMPTS = 5


def normalized(address: str) -> str:
    if not isinstance(address, str):
        raise BoletoEmailError("Informe um e-mail válido")
    email = address.strip().lower()
    if len(email) > 254 or not _EMAIL_RE.fullmatch(email):
        raise BoletoEmailError("Informe um e-mail válido")
    return email


def _db():
    path = os.getenv("BOLETO_EMAIL_DB", "/data/boleto_email.db")
    if not path.startswith("/") or not path.endswith(".db"):
        raise BoletoEmailError("Banco de validação de e-mails não configurado", 503)
    old = os.umask(0o077)
    try:
        conn = sqlite3.connect(path, timeout=10)
    finally:
        os.umask(old)
    try:
        os.chmod(path, 0o600)
        conn.execute("PRAGMA busy_timeout=10000")
        conn.execute("""CREATE TABLE IF NOT EXISTS boleto_alt_email_code (
            invoice_id TEXT NOT NULL,
            customer_id TEXT NOT NULL,
            destination TEXT NOT NULL,
            digest TEXT NOT NULL,
            expires_at INTEGER NOT NULL,
            requested_at INTEGER NOT NULL,
            attempts INTEGER NOT NULL DEFAULT 0,
            hour_start INTEGER NOT NULL,
            hour_requests INTEGER NOT NULL,
            consumed INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(invoice_id, customer_id, destination)
        )""")
        conn.commit()
        return conn
    except Exception:
        conn.close()
        raise


def _digest(invoice_id, customer_id, destination, code):
    key = os.getenv("SESSION_SECRET", "")
    if len(key) < 32:
        raise BoletoEmailError("Segurança da confirmação não configurada", 503)
    material = "|".join((str(invoice_id), str(customer_id), destination, code))
    return hmac.new(key.encode(), material.encode(), hashlib.sha256).hexdigest()


def _smtp_send(message):
    host, port, user, password, _ = _config()
    context = ssl.create_default_context()
    try:
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
            raise BoletoEmailError("O servidor de e-mail recusou o endereço", 502)
    except (smtplib.SMTPException, OSError, TimeoutError) as exc:
        raise BoletoEmailError("Não foi possível enviar o código ao e-mail informado", 502) from exc


def request_code(invoice_id: int, customer_id: str, address: str):
    """Envia apenas um código; nunca o boleto. Não retorna OTP na resposta."""
    email = normalized(address)
    _, _, _, _, sender = _config()  # falha antes de registrar se SMTP não habilitado
    now = int(time.time())
    code = f"{secrets.randbelow(1000000):06d}"
    digest = _digest(invoice_id, customer_id, email, code)
    conn = _db()
    try:
        with conn:
            conn.execute("BEGIN IMMEDIATE")
            old = conn.execute(
                "SELECT requested_at,hour_start,hour_requests FROM boleto_alt_email_code "
                "WHERE invoice_id=? AND customer_id=? AND destination=?",
                (str(invoice_id), customer_id, email),
            ).fetchone()
            if old and now - old[0] < MIN_RESEND:
                raise BoletoEmailError("Aguarde antes de solicitar outro código", 429)
            if old and now - old[1] < 3600 and old[2] >= MAX_HOUR:
                raise BoletoEmailError("Limite de códigos atingido. Tente mais tarde", 429)
            hour_start = old[1] if old and now - old[1] < 3600 else now
            requests = old[2] + 1 if old and now - old[1] < 3600 else 1
            conn.execute(
                "INSERT INTO boleto_alt_email_code "
                "(invoice_id,customer_id,destination,digest,expires_at,requested_at,"
                "attempts,hour_start,hour_requests,consumed) VALUES (?,?,?,?,?,?,0,?,?,0) "
                "ON CONFLICT(invoice_id,customer_id,destination) DO UPDATE SET "
                "digest=excluded.digest,expires_at=excluded.expires_at,"
                "requested_at=excluded.requested_at,attempts=0,"
                "hour_start=excluded.hour_start,hour_requests=excluded.hour_requests,consumed=0",
                (str(invoice_id), customer_id, email, digest, now + MAX_AGE,
                 now, hour_start, requests),
            )
    finally:
        conn.close()
    msg = EmailMessage()
    msg["From"] = sender
    msg["To"] = email
    msg["Subject"] = "ISM Telecom - confirme o endereço para receber boleto"
    msg.set_content(
        "Código para confirmar o recebimento de boleto ISM Telecom: " + code
        + "\nVálido por 10 minutos. Não compartilhe este código.\n"
        "Se você não solicitou, ignore esta mensagem."
    )
    _smtp_send(msg)
    return {"codigo_solicitado": True, "validade_segundos": MAX_AGE}


def consume_code(invoice_id: int, customer_id: str, address: str, code: str):
    email = normalized(address)
    if not isinstance(code, str) or not CODE_RE.fullmatch(code):
        raise BoletoEmailError("Informe o código de seis dígitos")
    now = int(time.time())
    conn = _db()
    invalid = False
    try:
        with conn:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute(
                "SELECT digest,expires_at,attempts,consumed FROM boleto_alt_email_code "
                "WHERE invoice_id=? AND customer_id=? AND destination=?",
                (str(invoice_id), customer_id, email),
            ).fetchone()
            if not row or row[3] or now > row[1] or row[2] >= MAX_ATTEMPTS:
                raise BoletoEmailError("Código expirado ou indisponível. Solicite outro", 422)
            conn.execute(
                "UPDATE boleto_alt_email_code SET attempts=attempts+1 "
                "WHERE invoice_id=? AND customer_id=? AND destination=?",
                (str(invoice_id), customer_id, email),
            )
            invalid = not hmac.compare_digest(
                row[0], _digest(invoice_id, customer_id, email, code)
            )
            if not invalid:
                # Consome antes do SMTP para impedir envio duplicado simultâneo.
                conn.execute(
                    "UPDATE boleto_alt_email_code SET consumed=1 "
                    "WHERE invoice_id=? AND customer_id=? AND destination=?",
                    (str(invoice_id), customer_id, email),
                )
    finally:
        conn.close()
    # Se o código errou, a contagem de tentativas já foi gravada.
    if invalid:
        raise BoletoEmailError("Código inválido", 422)
    return email


def send_verified_pdf(invoice_id, destination, pdf_base64):
    """Só pode ser chamado após consume_code e verificação da sessão/fatura no endpoint."""
    return send_pdf(invoice_id, normalized(destination), pdf_base64)
