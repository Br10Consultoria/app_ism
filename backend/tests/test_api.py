import os
import time
import pytest
from fastapi.testclient import TestClient
from app.main import app, create_session, read_session
from app.ixc import IXC, valid_cpf

os.environ["TERMINAL_KEY"] = "teste-chave-123456789012345678901234567890"
os.environ["SESSION_SECRET"] = "outra-chave-123456789012345678901234567890"

client = TestClient(app)
headers = {"X-Terminal-Key": os.environ["TERMINAL_KEY"]}


def test_cpf():
    assert valid_cpf("529.982.247-25")
    assert not valid_cpf("111.111.111-11")
    assert not valid_cpf("52998224726")


def test_requires_terminal():
    assert client.post("/api/v1/faturas", json={"cpf": "52998224725"}).status_code == 401


def test_no_invoice_id_leak():
    assert client.post("/api/v1/faturas/1/pix", headers=headers, json={"session": "invalid-invalid-token"}).status_code == 401


def test_session_roundtrip():
    assert read_session(create_session("123")) == "123"


def test_lead_requires_consent():
    r = client.post("/api/v1/interessados", headers=headers, json={
        "cpf": "52998224725", "telefone": "71999998888", "tecnico": "Técnico A",
        "consentimento": False
    })
    assert r.status_code == 422


def test_no_receiver_fails_not_success():
    for name in ("COMMERCIAL_WEBHOOK_URL", "SMTP_HOST", "COMMERCIAL_TO"):
        os.environ.pop(name, None)
    r = client.post("/api/v1/interessados", headers=headers, json={
        "cpf": "52998224725", "telefone": "71999998888", "tecnico": "Técnico A",
        "consentimento": True
    })
    assert r.status_code == 503


def test_safe_health():
    assert client.get("/health").json() == {"status": "ok"}
