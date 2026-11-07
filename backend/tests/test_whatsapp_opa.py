"""Testes isolados Opa! Suite: não enviam mensagens reais."""
import os
from datetime import date

import httpx
import pytest
from fastapi.testclient import TestClient

from app import main as module
from app import whatsapp_opa as opa


@pytest.fixture
def opa_env(monkeypatch, tmp_path):
    monkeypatch.setenv("TERMINAL_KEY", "terminal-ism-test-12345678901234567890123")
    monkeypatch.setenv("SESSION_SECRET", "session-ism-test-12345678901234567890123")
    monkeypatch.setenv("WHATSAPP_PROVIDER", "opa")
    monkeypatch.setenv("OPA_BASE_URL", "https://opa-ism.example.test")
    monkeypatch.setenv("OPA_API_TOKEN", "fake-opa-token")
    monkeypatch.setenv("OPA_CHANNEL_ID", "625ac335dadd05f1bd084040")
    monkeypatch.setenv("OPA_TEMPLATE_WELCOME_ID", "13197eb7dfca7928f233512f")
    monkeypatch.setenv("OPA_TEMPLATE_PIX_ID", "624c358355802dbdd2eb944a")
    monkeypatch.setenv("WHATSAPP_OUTBOX_DB", str(tmp_path / "outbox.db"))
    module.attempts.clear()
    captured = []
    session = module.create_session("33")
    headers = {"X-Terminal-Key": os.getenv("TERMINAL_KEY")}

    class IXCFake:
        async def customer_by_id(self, customer_id):
            if str(customer_id) != "33":
                return None
            return {
                "id": "33", "razao": "Cliente Teste",
                "data_cadastro": date.today().isoformat(),
                "ativo": "S", "whatsapp": "(71) 98888-7777"
            }

        async def invoice(self, invoice_id):
            if invoice_id == 443284:
                return {
                    "id": "443284", "id_cliente": "33", "status": "R",
                    "recebido_via_pix": "S", "valor_recebido": "1.00",
                    "valor_aberto": "0.00", "estornado": "N",
                    "aguardando_confirmacao_pagamento": "N",
                    "data_pagamento": "2026-11-07",
                }
            if invoice_id == 295590:
                return {
                    "id": "295590", "id_cliente": "33", "status": "R",
                    "recebido_via_pix": "N", "valor_recebido": "179.80",
                    "valor_aberto": "0.00", "estornado": "N"
                }
            if invoice_id == 777:
                return {"id": "777", "id_cliente": "99", "status": "R",
                        "recebido_via_pix": "S", "valor_recebido": "1.00"}
            return None

    monkeypatch.setattr(module, "IXC", IXCFake)

    async def mock_opa_send(kind, phone, values):
        captured.append({"kind": kind, "phone": phone, "vars": values})
        return "opa_message_id_123"

    monkeypatch.setattr(opa, "send_template", mock_opa_send)
    return TestClient(module.app), headers, session, captured


def test_opa_pix_confirmed_and_idempotent(opa_env):
    api, headers, session, calls = opa_env
    body = {"session": session, "consentimento": True}
    url = "/api/v1/whatsapp/pix/443284"

    assert api.post(url, json=body).status_code == 401
    assert api.post(url, headers=headers, json={**body, "consentimento": False}).status_code == 422
    assert api.post("/api/v1/whatsapp/pix/295590", headers=headers, json=body).status_code == 409
    assert api.post("/api/v1/whatsapp/pix/777", headers=headers, json=body).status_code == 404

    first = api.post(url, headers=headers, json=body)
    assert first.status_code == 200, first.text
    assert first.json()["situacao"] == "aceito_opa"
    assert first.json()["solicitado"] is True
    assert first.json()["destinatario_mascarado"].endswith("7777")
    assert "5571988887777" not in first.text
    assert calls == [{
        "kind": "pix", "phone": "5571988887777",
        "vars": ["Cliente", "1.00", "443284", "07/11/2026"]
    }]

    duplicate = api.post(url, headers=headers, json=body)
    assert duplicate.status_code == 200
    assert duplicate.json()["situacao"] == "ja_solicitado"
    assert len(calls) == 1


def test_opa_new_subscriber_welcome_only(opa_env):
    api, headers, session, calls = opa_env
    body = {"session": session, "consentimento": True, "confirma_novo_cliente": True}
    url = "/api/v1/whatsapp/boas-vindas"
    result = api.post(url, headers=headers, json=body)
    assert result.status_code == 200, result.text
    assert result.json()["situacao"] == "aceito_opa"
    assert calls == [{"kind": "boas_vindas", "phone": "5571988887777",
                      "vars": ["Cliente", "33"]}]


def test_opa_template_send_format(opa_env, monkeypatch):
    import asyncio
    _, _, _, calls = opa_env
    captured = {}

    class FakeHttpClient:
        def __init__(self, **kwargs):
            assert kwargs["follow_redirects"] is False
            assert kwargs["trust_env"] is False
        async def __aenter__(self):
            return self
        async def __aexit__(self, *args):
            return False
        async def post(self, url, headers, json):
            captured["url"] = url
            captured["headers"] = headers
            captured["json"] = json
            return httpx.Response(200, request=httpx.Request("POST", url), json={
                "status": "success", "code": 200,
                "data": {"message": "Template has been succesfully sent.",
                         "messageSentId": "test-message-id-123"}
            })

    monkeypatch.setattr(httpx, "AsyncClient", FakeHttpClient)
    id_ = asyncio.run(opa.send_template(
        "pix", "5571988887777",
        ["Cliente", "1.00", "443284", "07/11/2026"]
    ))
    assert id_ == "test-message-id-123"
    assert captured["url"] == "https://opa-ism.example.test/api/v1/template/send"
    assert captured["headers"]["Authorization"] == "Bearer fake-opa-token"
    assert captured["json"] == {
        "contato": {"canalCliente": "+5571988887777"},
        "template": {"_id": "624c358355802dbdd2eb944a",
                     "variaveis": ["Cliente", "1.00", "443284", "07/11/2026"]},
        "canal": "625ac335dadd05f1bd084040"
    }


def test_opa_invalid_configuration_never_sends(opa_env, monkeypatch):
    api, headers, session, calls = opa_env
    monkeypatch.delenv("OPA_API_TOKEN")
    res = api.post("/api/v1/whatsapp/pix/443284", headers=headers,
                   json={"session": session, "consentimento": True})
    assert res.status_code == 503
    assert calls == []


