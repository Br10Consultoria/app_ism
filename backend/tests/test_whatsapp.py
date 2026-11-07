"""Mocks de IXC e Meta; nunca enviam mensagens reais."""
import os
from datetime import date, timedelta

import httpx
import pytest
from fastapi.testclient import TestClient

from app import main as module
from app import whatsapp_meta as wa


@pytest.fixture
def setup_meta(monkeypatch, tmp_path):
    monkeypatch.setenv("TERMINAL_KEY", "terminal-ism-test-12345678901234567890123")
    monkeypatch.setenv("SESSION_SECRET", "session-ism-test-12345678901234567890123")
    monkeypatch.setenv("META_WHATSAPP_ACCESS_TOKEN", "test-meta-not-real")
    monkeypatch.setenv("META_WHATSAPP_PHONE_NUMBER_ID", "123456789")
    monkeypatch.setenv("META_TEMPLATE_WELCOME", "ism_boas_vindas_cliente")
    monkeypatch.setenv("META_TEMPLATE_PIX", "ism_pix_recebido")
    monkeypatch.setenv("META_GRAPH_VERSION", "v26.0")
    monkeypatch.setenv("META_OUTBOX_DB", str(tmp_path / "whatsapp_test.db"))
    monkeypatch.setenv("META_NEW_CLIENT_MAX_DAYS", "30")
    module.attempts.clear()
    session = module.create_session("33")
    captured = []

    class FakeIXC:
        async def customer_by_id(self, id_):
            if str(id_) != "33":
                return None
            return {
                "id": "33",
                "razao": "Miralvo Teste",
                "cnpj_cpf": "52998224725",
                "ativo": "S",
                "data_cadastro": date.today().isoformat(),
                "whatsapp": "71988887777",
            }

        async def invoice(self, invoice_id):
            if invoice_id == 443284:
                return {
                    "id": "443284", "id_cliente": "33", "status": "R",
                    "recebido_via_pix": "S", "valor_recebido": "1.00",
                    "valor_aberto": "0.00", "estornado": "N",
                    "aguardando_confirmacao_pagamento": "N",
                    "data_pagamento": "2026-11-07"
                }
            if invoice_id == 295590:
                return {
                    "id": "295590", "id_cliente": "33", "status": "R",
                    "recebido_via_pix": "N", "valor_recebido": "179.80",
                    "valor_aberto": "0.00"
                }
            if invoice_id == 554:
                return {
                    "id": "554", "id_cliente": "99", "status": "R",
                    "recebido_via_pix": "S", "valor_recebido": "5.00",
                    "valor_aberto": "0.00"
                }
            return None

    monkeypatch.setattr(module, "IXC", FakeIXC)

    async def fake_send(kind, number, params):
        captured.append((kind, number, params))
        return "wamid.TEST123"

    monkeypatch.setattr(wa, "send_template", fake_send)
    api = TestClient(module.app)
    headers = {"X-Terminal-Key": os.getenv("TERMINAL_KEY")}
    return api, headers, session, captured


def test_format_phone_date_and_welcome_eligibility():
    assert wa.customer_phone({"whatsapp": "(71) 98888-7777"}) == "5571988887777"
    assert wa.customer_phone({"whatsapp": "", "telefone_celular": "71 98888-7777"}) == "5571988887777"
    assert wa.customer_phone({"whatsapp": "7133330606"}) == ""
    assert wa.phone_mask("5571988887777").endswith("7777")
    today = date(2026, 11, 7)
    assert wa.new_customer({"data_cadastro": "2026-11-06", "ativo": "S"},
                           max_days=30, today=today)
    assert not wa.new_customer({"data_cadastro": "2026-08-01", "ativo": "S"},
                               max_days=30, today=today)
    assert not wa.new_customer({"data_cadastro": "", "ativo": "S"},
                               max_days=30, today=today)
    assert not wa.new_customer({"data_cadastro": "2026-11-06", "ativo": "N"},
                               max_days=30, today=today)
    assert wa.first_name({"razao": "  Miralvo   Teste  "}) == "Miralvo"


def test_meta_welcome_opt_in_recipient_and_deduplication(setup_meta):
    api, headers, session, sent = setup_meta
    endpoint = "/api/v1/whatsapp/boas-vindas"
    body = {"session": session, "consentimento": True, "confirma_novo_cliente": True}
    assert api.post(endpoint, json=body).status_code == 401
    assert api.post(endpoint, headers=headers,
                    json={**body, "consentimento": False}).status_code == 422
    first = api.post(endpoint, headers=headers, json=body)
    assert first.status_code == 200, first.text
    assert first.json()["situacao"] == "aceito_meta"
    assert first.json()["solicitado"] is True
    assert first.json()["destinatario_mascarado"].endswith("7777")
    assert sent == [("boas_vindas", "5571988887777", ["Miralvo", "33"])]
    second = api.post(endpoint, headers=headers, json=body)
    assert second.status_code == 200
    assert second.json()["situacao"] == "ja_solicitado"
    assert len(sent) == 1
    assert "5571988887777" not in first.text


def test_meta_pix_only_confirmed_for_same_customer(setup_meta):
    api, headers, session, sent = setup_meta
    body = {"session": session, "consentimento": True}
    assert api.post("/api/v1/whatsapp/pix/443284", json=body).status_code == 401
    assert api.post("/api/v1/whatsapp/pix/443284", headers=headers,
                    json={**body, "consentimento": False}).status_code == 422
    assert api.post("/api/v1/whatsapp/pix/295590", headers=headers,
                    json=body).status_code == 409
    assert api.post("/api/v1/whatsapp/pix/554", headers=headers,
                    json=body).status_code == 404
    result = api.post("/api/v1/whatsapp/pix/443284", headers=headers, json=body)
    assert result.status_code == 200, result.text
    assert result.json()["situacao"] == "aceito_meta"
    assert sent == [("pix", "5571988887777", ["Miralvo", "1.00", "443284", "07/11/2026"])]
    repeat = api.post("/api/v1/whatsapp/pix/443284", headers=headers, json=body)
    assert repeat.status_code == 200 and repeat.json()["situacao"] == "ja_solicitado"
    assert len(sent) == 1


def test_welcome_not_allowed_for_old_or_unknown_customer(setup_meta, monkeypatch):
    api, headers, session, sent = setup_meta

    class OldIXC:
        async def customer_by_id(self, id_):
            return {"id": "33", "ativo": "S",
                    "data_cadastro": "2023-01-01", "whatsapp": "71988887777"}

    monkeypatch.setattr(module, "IXC", OldIXC)
    body = {"session": session, "consentimento": True, "confirma_novo_cliente": True}
    res = api.post("/api/v1/whatsapp/boas-vindas", headers=headers, json=body)
    assert res.status_code == 409
    assert len(sent) == 0


def test_meta_missing_configuration_is_safe(setup_meta, monkeypatch):
    api, headers, session, sent = setup_meta
    monkeypatch.delenv("META_WHATSAPP_ACCESS_TOKEN", raising=False)
    result = api.post("/api/v1/whatsapp/pix/443284", headers=headers,
                      json={"session": session, "consentimento": True})
    assert result.status_code == 503
    assert not sent


def test_whatsapp_cloud_payload_and_approved_template(monkeypatch):
    monkeypatch.setenv("META_WHATSAPP_ACCESS_TOKEN", "not-real-token")
    monkeypatch.setenv("META_WHATSAPP_PHONE_NUMBER_ID", "123456789")
    monkeypatch.setenv("META_TEMPLATE_PIX", "ism_pix_recebido")
    monkeypatch.setenv("META_GRAPH_VERSION", "v26.0")
    captured = {}

    class FakeClient:
        def __init__(self, **kwargs):
            assert kwargs["follow_redirects"] is False

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            pass

        async def post(self, url, headers, json):
            captured["url"] = url
            captured["headers"] = headers
            captured["body"] = json
            return httpx.Response(
                200, request=httpx.Request("POST", url),
                json={"messages": [{"id": "wamid.TESTMETA"}]}
            )

    monkeypatch.setattr(httpx, "AsyncClient", FakeClient)
    import asyncio
    message_id = asyncio.run(
        wa.send_template("pix", "5571988887777",
                         ["Miralvo", "1.00", "443284", "07/11/2026"])
    )
    assert message_id == "wamid.TESTMETA"
    assert captured["url"] == "https://graph.facebook.com/v26.0/123456789/messages"
    assert captured["headers"]["Authorization"] == "Bearer not-real-token"
    assert captured["body"]["type"] == "template"
    assert captured["body"]["template"]["name"] == "ism_pix_recebido"
    assert len(captured["body"]["template"]["components"][0]["parameters"]) == 4
