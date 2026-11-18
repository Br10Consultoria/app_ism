"""Quero contratar: apenas convite ao próprio interessado; sem envio para comercial interno."""
import os
import sqlite3
import httpx
from fastapi.testclient import TestClient
from app import main as module
from app import whatsapp_opa
from app.commercial_leads import list_leads

client = TestClient(module.app)


def payload():
    return {
        "nome": "Interessado Teste",
        "cpf": "52998224725",  # documento de teste; não é dado do usuário
        "telefone": "71988887777",
        "id_cliente": "",
        "tecnico": "Tecnico de campo",
        "consentimento": True,
    }


def config(monkeypatch, tmp_path):
    monkeypatch.setenv("TERMINAL_KEY", "terminal-comercial-test-12345678901234567890")
    monkeypatch.setenv("SESSION_SECRET", "session-comercial-test-12345678901234567890")
    monkeypatch.setenv("LEADS_DB", str(tmp_path / "leads.db"))
    monkeypatch.setenv("WHATSAPP_PROVIDER", "opa")
    monkeypatch.setenv("OPA_BASE_URL", "https://opa.example.test")
    monkeypatch.setenv("OPA_API_TOKEN", "TOKEN_FAKE_NAO_REAL")
    monkeypatch.setenv("OPA_CHANNEL_ID", "679cc77501d3afa6ad0a1178")
    monkeypatch.setenv("OPA_TEMPLATE_LEAD_ID", "6924bc62fe20def174e09d5c")
    monkeypatch.setenv("OPA_COMMERCIAL_PHONE", "71977776666")
    monkeypatch.setenv("OPA_TEMPLATE_PROSPECT_ID", "6aa3fcaad95fdf7490e33108")
    monkeypatch.setenv("OPA_PROSPECT_WHATSAPP_ENABLED", "false")
    monkeypatch.setenv("COMMERCIAL_WEBHOOK_URL", "https://internal.example.test/leads")
    monkeypatch.setenv("SMTP_HOST", "smtp.internal.example.test")
    monkeypatch.setenv("COMMERCIAL_TO", "internal@example.test")
    module.attempts.clear()
    return {"X-Terminal-Key": os.getenv("TERMINAL_KEY")}


def test_internal_channel_never_receives_automatic_new_interest(monkeypatch, tmp_path):
    headers = config(monkeypatch, tmp_path)
    calls = []

    async def fake_send(*args, **kwargs):
        calls.append((args, kwargs))
        return "unexpected"

    monkeypatch.setattr(whatsapp_opa, "send_template", fake_send)
    response = client.post("/api/v1/interessados", headers=headers, json=payload())
    assert response.status_code == 200, response.text
    assert response.json()["situacao"] == "pendente"
    assert response.json()["enviado"] is False
    assert calls == []
    saved = list_leads("pendente")
    assert len(saved) == 1
    assert saved[0]["cpf"] == payload()["cpf"]
    assert saved[0]["tecnico"] == payload()["tecnico"]
    assert saved[0]["telefone"] == payload()["telefone"]


def test_prospect_only_even_with_internal_templates_enabled(monkeypatch, tmp_path):
    headers = config(monkeypatch, tmp_path)
    monkeypatch.setenv("OPA_PROSPECT_WHATSAPP_ENABLED", "true")
    calls = []

    async def fake_send(kind, phone, variables, **kwargs):
        calls.append((kind, phone, variables, kwargs))
        return "opa-convite-123"

    monkeypatch.setattr(whatsapp_opa, "send_template", fake_send)
    first = client.post("/api/v1/interessados", headers=headers, json=payload())
    assert first.status_code == 200, first.text
    assert first.json()["situacao"] == "mensagem_cliente"
    assert first.json()["enviado"] is True
    assert "52998224725" not in first.text
    assert calls == [(
        "prospecto", "5571988887777", ["Interessado", "ISM TELECOM"], {}
    )]
    stored = list_leads("encaminhado")
    assert len(stored) == 1
    assert stored[0]["cpf"] == payload()["cpf"]
    assert stored[0]["tecnico"] == payload()["tecnico"]
    again = client.post("/api/v1/interessados", headers=headers, json=payload())
    assert again.json()["protocolo"] == first.json()["protocolo"]
    assert len(calls) == 1


def test_failed_outbound_message_preserves_interest_for_review(monkeypatch, tmp_path):
    headers = config(monkeypatch, tmp_path)
    monkeypatch.setenv("OPA_PROSPECT_WHATSAPP_ENABLED", "true")

    async def fail(*args, **kwargs):
        raise httpx.ReadTimeout("timeout simulado")

    monkeypatch.setattr(whatsapp_opa, "send_template", fail)
    response = client.post("/api/v1/interessados", headers=headers, json=payload())
    assert response.status_code == 200
    assert response.json()["situacao"] == "revisar"
    assert len(list_leads("revisar")) == 1


def test_leads_db_private_permissions(monkeypatch, tmp_path):
    monkeypatch.setenv("LEADS_DB", str(tmp_path / "leadbook.db"))
    from app.commercial_leads import save_interest
    saved = save_interest(payload())
    assert saved["estado"] == "pendente"
    assert (tmp_path / "leadbook.db").stat().st_mode & 0o077 == 0
    with sqlite3.connect(tmp_path / "leadbook.db") as db:
        row = db.execute("SELECT cpf, tecnico FROM interessados").fetchone()
        assert row == (payload()["cpf"], payload()["tecnico"])
