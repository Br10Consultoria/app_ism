"""Provider Opa! Suite para envio de templates WhatsApp já aprovados.

O terminal Android envia apenas o pedido e a sessão; destinatário e dados são
reconsultados no IXC. Credenciais Opa ficam somente no backend em .env.
"""
import os
import re
from urllib.parse import urlparse

import httpx

from .whatsapp_meta import (
    WhatsAppError, customer_phone, phone_mask, first_name, new_customer,
    _event_key, _claim, _mark, _release,
)


def template_env_key(kind):
    keys = {
        "boas_vindas": "OPA_TEMPLATE_WELCOME_ID",
        "pix": "OPA_TEMPLATE_PIX_ID",
        "interessados": "OPA_TEMPLATE_LEAD_ID",
    }
    if kind not in keys:
        raise WhatsAppError("Tipo de mensagem Opa inválido", 422)
    return keys[kind]


def configured(kind):
    key = template_env_key(kind)
    return bool(
        os.getenv("OPA_BASE_URL", "").strip()
        and os.getenv("OPA_API_TOKEN", "").strip()
        and os.getenv("OPA_CHANNEL_ID", "").strip()
        and os.getenv(key, "").strip()
    )


def _config(kind):
    if not configured(kind):
        raise WhatsAppError(
            "Opa! Suite não configurado: endereço, token, canal ou template ausente", 503)
    base = os.getenv("OPA_BASE_URL", "").strip().rstrip("/")
    parsed = urlparse(base)
    if parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password:
        raise WhatsAppError("Opa! Suite precisa de endereço HTTPS válido", 503)
    if parsed.path not in ("", "/"):
        raise WhatsAppError("OPA_BASE_URL deve conter apenas o domínio HTTPS", 503)
    channel = os.getenv("OPA_CHANNEL_ID", "").strip()
    template_id = os.getenv(template_env_key(kind), "").strip()
    if not re.fullmatch(r"[a-zA-Z0-9]{20,40}", channel):
        raise WhatsAppError("ID de canal do Opa! Suite inválido", 503)
    if not re.fullmatch(r"[a-zA-Z0-9]{20,40}", template_id):
        raise WhatsAppError("ID de template do Opa! Suite inválido", 503)
    return base, os.getenv("OPA_API_TOKEN", "").strip(), channel, template_id


async def send_template(kind, phone, parameters):
    """Retorna ID interno da mensagem do Opa; não garante entrega ao WhatsApp."""
    base, token, channel, template_id = _config(kind)
    if not re.fullmatch(r"55[1-9][0-9]9[0-9]{8}", phone):
        raise WhatsAppError("Número celular cadastrado no IXC inválido", 422)

    variables = []
    for value in parameters:
        clean = re.sub(r"[\r\n\t]+", " ", str(value)).strip()
        if not clean or len(clean) > 200:
            raise WhatsAppError("Variável do modelo Opa! Suite inválida", 422)
        variables.append(clean)

    body = {
        "contato": {"canalCliente": "+" + phone},
        "template": {"_id": template_id, "variaveis": variables},
        "canal": channel,
    }
    try:
        async with httpx.AsyncClient(
            timeout=15, follow_redirects=False, trust_env=False
        ) as client:
            response = await client.post(
                base + "/api/v1/template/send",
                headers={
                    "Authorization": "Bearer " + token,
                    "Content-Type": "application/json",
                },
                json=body,
            )
            response.raise_for_status()
            result = response.json()
    except httpx.HTTPStatusError as error:
        status = error.response.status_code
        raise WhatsAppError(
            "Opa! Suite recusou a mensagem; confira permissões, canal e modelo aprovado.",
            502,
            retry_after_config=400 <= status < 500,
        ) from error
    except (httpx.HTTPError, ValueError) as error:
        raise WhatsAppError(
            "Não foi possível confirmar o envio no Opa! Suite. Consulte o histórico "
            "da plataforma antes de tentar novamente.",
            502,
        ) from error

    if not isinstance(result, dict) or result.get("status") != "success":
        raise WhatsAppError("Opa! Suite não confirmou o envio. Confira o histórico antes de repetir.", 502)
    data = result.get("data")
    message_id = data.get("messageSentId") if isinstance(data, dict) else None
    if not isinstance(message_id, str) or not message_id.strip():
        raise WhatsAppError("Opa! Suite não retornou ID da mensagem; confirme antes de repetir.", 502)
    return message_id.strip()


async def send_once(kind, customer_id, invoice_id, phone, parameters):
    # Identificadores e permissão são validados antes de bloquear reenvios.
    _config(kind)
    if not phone:
        raise WhatsAppError("Cliente não possui número válido no cadastro IXC", 422)
    event = _event_key("opa_" + kind, customer_id, invoice_id)
    if not _claim(event, "opa_" + kind):
        return {
            "solicitado": False,
            "situacao": "ja_solicitado",
            "mensagem": "Esta mensagem já foi solicitada pelo Opa! Suite; verifique o histórico.",
        }
    try:
        message_id = await send_template(kind, phone, parameters)
    except WhatsAppError as error:
        if error.retry_after_config:
            _release(event)
        else:
            _mark(event, "revisar")
        raise
    _mark(event, "aceito_opa", message_id)
    return {
        "solicitado": True,
        "situacao": "aceito_opa",
        "mensagem": "Opa! Suite aceitou o pedido; entrega ao cliente ainda não confirmada.",
        "opa_message_id": message_id,
    }
