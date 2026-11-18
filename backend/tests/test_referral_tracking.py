"""Captação ISM: cadastro completo, origem QR e link sem dados pessoais."""
import os
import uuid
from urllib.parse import unquote, urlparse, parse_qs

from fastapi.testclient import TestClient

from app import main, whatsapp_opa
from app.commercial_leads import lookup_referral, list_leads
from app.terminal_activation import register_device

client = TestClient(main.app, follow_redirects=False)
KEY = "terminal-teste-ism-chave-12345678901234567890"
ADMIN = "admin-teste-ism-chave-1234567890123456789012345"
CPF = "52998224725"  # CPF sintético para teste do validador, não é dado do usuário


def config(monkeypatch, tmp_path, *, new_template=True):
    monkeypatch.setenv("TERMINAL_KEY", KEY)
    monkeypatch.setenv("SESSION_SECRET", "session-test-ism-12345678901234567890123456")
    monkeypatch.setenv("ISM_ADMIN_KEY", ADMIN)
    monkeypatch.setenv("LEADS_DB", str(tmp_path / "leads.db"))
    monkeypatch.setenv("TERMINAL_ACTIVATION_DB", str(tmp_path / "activation.db"))
    monkeypatch.setenv("WHATSAPP_PROVIDER", "opa")
    monkeypatch.setenv("OPA_BASE_URL", "https://opa.example.test")
    monkeypatch.setenv("OPA_API_TOKEN", "TOKEN_TESTE")
    monkeypatch.setenv("OPA_CHANNEL_ID", "679cc77501d3afa6ad0a1178")
    monkeypatch.setenv("OPA_PROSPECT_WHATSAPP_ENABLED", "true")
    monkeypatch.setenv("OPA_TEMPLATE_PROSPECT_ID", "6aa3fcaad95fdf7490e33108")
    if new_template:
        monkeypatch.setenv("OPA_TEMPLATE_PROSPECT_LINK_ID", "6aa3fcaad95fdf7490e33200")
    else:
        monkeypatch.delenv("OPA_TEMPLATE_PROSPECT_LINK_ID", raising=False)
    monkeypatch.setenv("COMMERCIAL_WHATSAPP_PHONE", "71977776666")
    with main.rate_lock:
        main.attempts.clear()
    installation = str(uuid.uuid4())
    assert register_device(installation) == "ISM-0001"
    return installation


def payload(installation):
    return {
        "nome": "Cliente Teste", "cpf": CPF, "telefone": "71988887777",
        "id_cliente": "", "tecnico": "Tecnico da ISM",
        "installation_uuid": installation, "consentimento": True
    }


def test_link_template_only_client_and_terminal_attribution(monkeypatch, tmp_path):
    installation = config(monkeypatch, tmp_path)
    sent = []
    async def fake_send(kind, phone, variables, **kwargs):
        sent.append((kind, phone, variables, kwargs))
        return "opa-id-123"
    monkeypatch.setattr(whatsapp_opa, "send_template", fake_send)
    response = client.post("/api/v1/interessados",
                           headers={"X-Terminal-Key": KEY}, json=payload(installation))
    assert response.status_code == 200, response.text
    data = response.json()
    assert data["situacao"] == "mensagem_cliente"
    assert data["link_incluido"] is True
    assert data["indicacao"].startswith("CAP-")
    assert data["link_indicacao"].endswith("/i/" + data["indicacao"])
    assert sent == [("prospecto_indicacao", "5571988887777",
                     ["Cliente", "ISM TELECOM", data["link_indicacao"]], {})]
    assert CPF not in str(sent)
    saved = lookup_referral(data["protocolo"])
    assert saved["cpf"] == CPF
    assert saved["tecnico"] == "Tecnico da ISM"
    assert saved["terminal_id"] == "ISM-0001"
    assert saved["visitas_link"] == 0

    # Link público: só protocolo; nunca revela CPF, telefone, nome ou técnico.
    redirect = client.get("/i/" + data["indicacao"])
    assert redirect.status_code == 302
    assert redirect.headers["cache-control"].startswith("no-store")
    assert "wa.me/5571977776666" in redirect.headers["location"]
    query = parse_qs(urlparse(redirect.headers["location"]).query)
    assert data["indicacao"] in query["text"][0]
    assert CPF not in redirect.headers["location"]
    assert lookup_referral(data["protocolo"])["visitas_link"] == 1

    # A lista completa requer credencial administrativa DIFERENTE da maquineta.
    admin_url = "/api/v1/admin/captacoes/" + data["indicacao"]
    assert client.get(admin_url).status_code == 401
    assert client.get(admin_url, headers={"X-ISM-ADMIN-KEY": KEY}).status_code == 401
    secret = client.get(admin_url, headers={"X-ISM-ADMIN-KEY": ADMIN})
    assert secret.status_code == 200
    assert secret.json()["terminal_id"] == "ISM-0001"
    assert secret.json()["cpf"] == CPF
    assert secret.json()["visitas_link"] == 1


def test_fallback_existing_template_does_not_claim_link_sent(monkeypatch, tmp_path):
    installation = config(monkeypatch, tmp_path, new_template=False)
    called = []
    async def fake_send(kind, phone, variables, **kwargs):
        called.append((kind, phone, variables, kwargs))
        return "opa-legacy"
    monkeypatch.setattr(whatsapp_opa, "send_template", fake_send)
    response = client.post("/api/v1/interessados",
                           headers={"X-Terminal-Key": KEY}, json=payload(installation))
    assert response.status_code == 200
    assert response.json()["link_incluido"] is False
    assert called == [("prospecto", "5571988887777",
                      ["Cliente", "ISM TELECOM"], {})]


def test_unregistered_terminal_is_not_attributed_to_another_ism(monkeypatch, tmp_path):
    config(monkeypatch, tmp_path)
    bad_installation = str(uuid.uuid4())
    response = client.post("/api/v1/interessados",
                           headers={"X-Terminal-Key": KEY}, json=payload(bad_installation))
    assert response.status_code == 409
    assert list_leads("pendente") == []


def test_invalid_referral_does_not_redirect_or_disclose_info(monkeypatch, tmp_path):
    config(monkeypatch, tmp_path)
    assert client.get("/i/CAP-NAOEXISTE").status_code == 404
    assert client.get("/api/v1/admin/captacoes/CAP-123456789ABC").status_code == 401
    monkeypatch.setenv("COMMERCIAL_WHATSAPP_PHONE", "")
    assert client.get("/i/CAP-123456789ABC").status_code == 503
