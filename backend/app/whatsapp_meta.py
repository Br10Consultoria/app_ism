"""Envio opt-in de templates WhatsApp Cloud API para clientes IXC.

Nenhuma credencial Meta é compartilhada com o Android. O backend só aceita
telefones lidos do IXC e nunca toma o número indicado pela maquininha.
"""
from datetime import date, datetime
import hashlib
import hmac
import os
import re
import sqlite3
from threading import Lock

import httpx

from .ixc import digits, pick


class WhatsAppError(Exception):
    def __init__(self, message, status_code=503, retry_after_config=False):
        super().__init__(message)
        self.status_code = status_code
        self.retry_after_config = retry_after_config


def customer_phone(customer):
    """BR mobile E.164 (sem '+'); nunca aceitar telefone informado pelo terminal."""
    if not isinstance(customer, dict):
        return ""
    for key in ("whatsapp", "telefone_celular", "celular"):
        number = digits(customer.get(key, ""))
        if len(number) == 11 and number[2] == "9" and number[:2] != "00":
            return "55" + number
        if len(number) == 13 and number.startswith("55") and number[4] == "9":
            return number
    return ""


def phone_mask(number):
    return ("(**) *****-" + number[-4:]) if len(number) == 13 else ""


def customer_name(customer):
    name = pick(customer, ("razao", "nome", "nome_razao", "fantasia"))
    # Meta template parameters must not contain control chars or newlines.
    name = re.sub(r"\s+", " ", name).strip()
    return name[:80] if name else ""


def first_name(customer):
    full_name = customer_name(customer)
    return full_name.split(" ", 1)[0] if full_name else "Cliente"


def new_customer(customer, max_days=None, today=None):
    """Somente cadastros recentes comprovados pelo IXC, fail-closed sem data."""
    if not isinstance(customer, dict):
        return False
    if str(customer.get("ativo") or "S").upper().strip() == "N":
        return False
    value = pick(customer, ("data_cadastro", "data_cadastro_sistema"))
    if not value:
        return False
    parsed = None
    for fmt in ("%Y-%m-%d", "%d/%m/%Y", "%Y-%m-%d %H:%M:%S",
                "%d/%m/%Y %H:%M:%S"):
        try:
            parsed = datetime.strptime(value[:19], fmt).date()
            break
        except ValueError:
            continue
    if parsed is None:
        return False
    if max_days is None:
        try:
            max_days = int(os.getenv("META_NEW_CLIENT_MAX_DAYS", "30"))
        except ValueError:
            return False
    if not 1 <= max_days <= 90:
        return False
    age = ((today or date.today()) - parsed).days
    return 0 <= age <= max_days


def configured(kind):
    name = "META_TEMPLATE_WELCOME" if kind == "boas_vindas" else "META_TEMPLATE_PIX"
    return bool(
        os.getenv("META_WHATSAPP_ACCESS_TOKEN", "").strip()
        and os.getenv("META_WHATSAPP_PHONE_NUMBER_ID", "").isdigit()
        and os.getenv(name, "").strip()
    )


def _config(kind):
    template = ("META_TEMPLATE_WELCOME" if kind == "boas_vindas"
                else "META_TEMPLATE_PIX")
    if not configured(kind):
        raise WhatsAppError(
            "WhatsApp Meta ainda não configurado: credenciais ou template ausente", 503)
    version = os.getenv("META_GRAPH_VERSION", "v26.0").strip()
    language = os.getenv("META_TEMPLATE_LANGUAGE", "pt_BR").strip()
    name = os.getenv(template, "").strip()
    phone_id = os.getenv("META_WHATSAPP_PHONE_NUMBER_ID", "").strip()
    token = os.getenv("META_WHATSAPP_ACCESS_TOKEN", "").strip()
    if not re.fullmatch(r"v[0-9]{1,2}\.[0-9]+", version):
        raise WhatsAppError("Versão da API Meta inválida", 503)
    if not re.fullmatch(r"[a-z][a-z0-9_]{0,511}", name):
        raise WhatsAppError("Nome de template Meta inválido", 503)
    if not re.fullmatch(r"[a-z]{2}_[A-Z]{2}", language):
        raise WhatsAppError("Idioma de template Meta inválido", 503)
    return version, language, name, phone_id, token


async def send_template(kind, phone, parameters):
    """Cloud API /messages; retorno é aceitação, NÃO confirmação de entrega."""
    version, language, template, sender_id, token = _config(kind)
    if not re.fullmatch(r"55[1-9][0-9]9[0-9]{8}", phone):
        raise WhatsAppError("Telefone celular WhatsApp inválido no IXC", 422)
    values = []
    for value in parameters:
        value = re.sub(r"[\r\n\t]+", " ", str(value)).strip()
        if not value or len(value) > 200:
            raise WhatsAppError("Parâmetro do template inválido", 422)
        values.append({"type": "text", "text": value})

    body = {
        "messaging_product": "whatsapp",
        "recipient_type": "individual",
        "to": phone,
        "type": "template",
        "template": {
            "name": template,
            "language": {"code": language},
            "components": [{"type": "body", "parameters": values}],
        },
    }
    try:
        async with httpx.AsyncClient(timeout=15, follow_redirects=False,
                                    trust_env=False) as client:
            response = await client.post(
                f"https://graph.facebook.com/{version}/{sender_id}/messages",
                headers={"Authorization": "Bearer " + token,
                         "Content-Type": "application/json"},
                json=body,
            )
            response.raise_for_status()
            result = response.json()
    except httpx.HTTPStatusError as exc:
        # Resposta 4xx definitiva: nenhuma mensagem foi aceita; após ajustar
        # token/template, o operador pode tentar novamente. 5xx é incerto.
        raise WhatsAppError(
            "Meta recusou a solicitação. Confira token e template aprovado.",
            502, retry_after_config=400 <= exc.response.status_code < 500
        ) from exc
    except (httpx.HTTPError, ValueError) as exc:
        raise WhatsAppError(
            "Não foi possível confirmar aceitação pela Meta. Verifique no Business Manager antes de repetir.",
            502) from exc
    messages = result.get("messages", []) if isinstance(result, dict) else []
    message_id = messages[0].get("id", "") if messages and isinstance(messages[0], dict) else ""
    if not isinstance(message_id, str) or not message_id.startswith("wamid."):
        raise WhatsAppError("Meta não retornou identificação de mensagem; confirme antes de repetir", 502)
    return message_id


_outbox_lock = Lock()


def _db_path():
    return os.getenv("WHATSAPP_OUTBOX_DB") or os.getenv("META_OUTBOX_DB", "/data/whatsapp_outbox.db")


def _event_key(kind, customer_id, invoice_id):
    key = os.getenv("SESSION_SECRET", "").encode()
    if len(key) < 32:
        raise WhatsAppError("Segredo da sessão não configurado", 503)
    return hmac.new(key, f"{kind}:{customer_id}:{invoice_id}".encode(),
                    hashlib.sha256).hexdigest()


def _connect():
    filename = _db_path()
    if not filename.endswith(".db"):
        raise WhatsAppError("Caminho do banco de auditoria inválido", 503)
    try:
        connection = sqlite3.connect(filename, timeout=5)
        connection.execute(
            "CREATE TABLE IF NOT EXISTS envio_whatsapp "
            "(evento TEXT PRIMARY KEY, tipo TEXT NOT NULL, estado TEXT NOT NULL, "
            "mensagem_id TEXT NOT NULL DEFAULT '', criado_em TEXT DEFAULT CURRENT_TIMESTAMP)")
        connection.commit()
        return connection
    except sqlite3.Error as exc:
        raise WhatsAppError("Banco de auditoria WhatsApp indisponível", 503) from exc


def _claim(event, kind):
    with _outbox_lock:
        connection = _connect()
        try:
            try:
                connection.execute(
                    "INSERT INTO envio_whatsapp(evento,tipo,estado) VALUES (?,?,'em_processamento')",
                    (event, kind))
                connection.commit()
                return True
            except sqlite3.IntegrityError:
                return False
        finally:
            connection.close()


def _mark(event, state, msg_id=""):
    with _outbox_lock:
        connection = _connect()
        try:
            connection.execute(
                "UPDATE envio_whatsapp SET estado=?, mensagem_id=? WHERE evento=?",
                (state, msg_id[:255], event))
            connection.commit()
        finally:
            connection.close()


def _release(event):
    with _outbox_lock:
        connection = _connect()
        try:
            connection.execute(
                "DELETE FROM envio_whatsapp WHERE evento=? AND estado='em_processamento'",
                (event,))
            connection.commit()
        finally:
            connection.close()


async def send_once(kind, customer_id, invoice_id, phone, parameters):
    # Validar configuração/template antes de bloquear idempotência.
    _config(kind)
    if not phone:
        raise WhatsAppError("Cliente sem WhatsApp celular válido no IXC", 422)
    event = _event_key(kind, customer_id, invoice_id)
    if not _claim(event, kind):
        return {"solicitado": False, "situacao": "ja_solicitado",
                "mensagem": "Mensagem já solicitada anteriormente. Consulte o histórico na Meta."}
    try:
        message_id = await send_template(kind, phone, parameters)
    except WhatsAppError as error:
        if error.retry_after_config:
            # Rejeição explícita HTTP 4xx: seguro corrigir configuração e reenviar.
            _release(event)
        else:
            # Timeout, 5xx, resposta sem ID: estado de entrega incerto.
            _mark(event, "revisar")
        raise
    _mark(event, "aceito_meta", message_id)
    return {"solicitado": True, "situacao": "aceito_meta",
            "mensagem": "Meta aceitou a solicitação. A entrega não foi confirmada.",
            "meta_message_id": message_id}
