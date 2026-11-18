"""Sem envio real: testa opt-in de mensagens ao interessado pelo Opa! Suite."""
import asyncio

import httpx
from fastapi.testclient import TestClient

from app import main as app_main
from app import whatsapp_opa
from app.commercial_leads import list_leads


def env_prospect(monkeypatch, tmp_path, enabled=True):
    monkeypatch.setenv("TERMINAL_KEY", "terminal-prospect-ism-test-key-1234567890123456")
    monkeypatch.setenv("SESSION_SECRET", "session-prospect-ism-test-key-1234567890123456")
    monkeypatch.setenv("LEADS_DB", str(tmp_path / "leads.db"))
    monkeypatch.setenv("WHATSAPP_PROVIDER", "opa")
    monkeypatch.setenv("OPA_BASE_URL", "https://opa.example.test")
    monkeypatch.setenv("OPA_API_TOKEN", "test-token-not-real")
    monkeypatch.setenv("OPA_CHANNEL_ID", "679cc77501d3afa6ad0a1178")
    monkeypatch.setenv("OPA_TEMPLATE_PROSPECT_ID", "6aa3fcaad95fdf7490e33108")
    monkeypatch.setenv("OPA_PROSPECT_WHATSAPP_ENABLED", "true" if enabled else "false")
    for k in ("OPA_TEMPLATE_LEAD_ID", "OPA_COMMERCIAL_PHONE",
              "COMMERCIAL_WEBHOOK_URL", "SMTP_HOST", "COMMERCIAL_TO"):
        monkeypatch.delenv(k, raising=False)
    app_main.attempts.clear()
    return TestClient(app_main.app), {
        "X-Terminal-Key": "terminal-prospect-ism-test-key-1234567890123456"
    }


def interest():
    return {
        "nome": "Interessado Autorizado",
        "cpf": "52998224725",
        "telefone": "71988887777",
        "id_cliente": "",
        "tecnico": "Equipe campo",
        "consentimento": True,
    }


def test_prospect_opa_sends_only_after_opt_in(monkeypatch, tmp_path):
    client, headers = env_prospect(monkeypatch, tmp_path)
    sent = []

    async def fake_send(kind, phone, variables, **kwargs):
        sent.append((kind, phone, variables, kwargs))
        return "opa-accepted-abc"

    monkeypatch.setattr(whatsapp_opa, "send_template", fake_send)
    assert client.post("/api/v1/interessados", json=interest()).status_code == 401
    response = client.post("/api/v1/interessados", headers=headers, json=interest())
    assert response.status_code == 200, response.text
    assert response.json()["enviado"] is True
    assert response.json()["situacao"] == "mensagem_cliente"
    assert len(response.json()["protocolo"]) == 12
    assert "52998224725" not in response.text
    assert "71988887777" not in response.text
    assert sent == [
        ("prospecto", "5571988887777",
         ["Interessado", "ISM TELECOM"], {})
    ]
    registered = list_leads("encaminhado")
    assert len(registered) == 1
    repeat = client.post("/api/v1/interessados", headers=headers, json=interest())
    assert repeat.status_code == 200
    assert repeat.json()["situacao"] == "mensagem_cliente"
    assert repeat.json()["protocolo"] == response.json()["protocolo"]
    assert len(sent) == 1


def test_prospect_opa_is_off_by_default_and_works_with_legacy_leads(monkeypatch, tmp_path):
    client, headers = env_prospect(monkeypatch, tmp_path, enabled=False)
    sent = []

    async def fake_send(*args, **kwargs):
        sent.append((args, kwargs))
        return "should-not-send"

    monkeypatch.setattr(whatsapp_opa, "send_template", fake_send)
    response = client.post("/api/v1/interessados", headers=headers, json=interest())
    assert response.status_code == 200
    assert response.json()["enviado"] is False
    assert response.json()["situacao"] == "pendente"
    assert not sent
    assert len(list_leads("pendente")) == 1


def test_prospect_requires_consent_without_any_message(monkeypatch, tmp_path):
    client, headers = env_prospect(monkeypatch, tmp_path)
    response = client.post("/api/v1/interessados", headers=headers,
                           json={**interest(), "consentimento": False})
    assert response.status_code == 422
    assert list_leads("pendente") == []


def test_prospect_phone_landline_not_messaged(monkeypatch, tmp_path):
    client, headers = env_prospect(monkeypatch, tmp_path)
    response = client.post("/api/v1/interessados", headers=headers,
                           json={**interest(), "telefone": "7133340606"})
    assert response.status_code == 200
    assert response.json()["situacao"] == "pendente"


def test_prospect_uncertain_reply_preserves_case_for_review(monkeypatch, tmp_path):
    client, headers = env_prospect(monkeypatch, tmp_path)

    async def fake_fail(*args, **kwargs):
        raise whatsapp_opa.WhatsAppError("timeout resultado incerto", 502)

    monkeypatch.setattr(whatsapp_opa, "send_template", fake_fail)
    response = client.post("/api/v1/interessados", headers=headers, json=interest())
    assert response.status_code == 200
    assert response.json()["situacao"] == "revisar"
    assert len(list_leads("revisar")) == 1


def test_prospect_opa_payload_for_existing_template(monkeypatch):
    monkeypatch.setenv("OPA_BASE_URL", "https://opa.example.test")
    monkeypatch.setenv("OPA_API_TOKEN", "test")
    monkeypatch.setenv("OPA_CHANNEL_ID", "679cc77501d3afa6ad0a1178")
    monkeypatch.setenv("OPA_TEMPLATE_PROSPECT_ID", "6aa3fcaad95fdf7490e33108")
    captured = {}

    class FakeClient:
        def __init__(self, **kwargs):
            assert kwargs["follow_redirects"] is False
        async def __aenter__(self): return self
        async def __aexit__(self, *args): return False
        async def post(self, url, headers, json):
            captured["url"] = url
            captured["body"] = json
            return httpx.Response(200, request=httpx.Request("POST", url),
                                  json={"status": "success",
                                        "data": {"messageSentId": "opa-message-id"}})

    monkeypatch.setattr(httpx, "AsyncClient", FakeClient)
    result = asyncio.run(whatsapp_opa.send_template(
        "prospecto", "5571988887777", ["Interessado", "ISM TELECOM"],
        open_customer_service=True
    ))
    assert result == "opa-message-id"
    assert captured["url"] == "https://opa.example.test/api/v1/template/send"
    assert captured["body"] == {
        "contato": {"canalCliente": "+5571988887777"},
        "template": {"_id": "6aa3fcaad95fdf7490e33108",
                     "variaveis": ["Interessado", "ISM TELECOM"]},
        "canal": "679cc77501d3afa6ad0a1178",
        "openCustomerService": True
    }
