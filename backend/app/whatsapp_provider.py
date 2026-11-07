"""Seleciona o provedor de WhatsApp no servidor (nunca no APK).

OPA é o recomendado quando IXC já está integrado ao Opa! Suite.
META permanece disponível apenas para ambientes que realmente usam Cloud API direta.
"""
import os

from .whatsapp_meta import (
    WhatsAppError, customer_phone, phone_mask, first_name, new_customer,
)
from . import whatsapp_opa, whatsapp_meta


def _provider():
    choice = os.getenv("WHATSAPP_PROVIDER", "meta").strip().lower()
    if choice not in ("meta", "opa"):
        raise WhatsAppError("WHATSAPP_PROVIDER inválido", 503)
    return whatsapp_opa if choice == "opa" else whatsapp_meta


def configured(kind):
    try:
        return _provider().configured(kind)
    except WhatsAppError:
        return False


async def send_once(kind, customer_id, invoice_id, phone, parameters):
    return await _provider().send_once(kind, customer_id, invoice_id, phone, parameters)
