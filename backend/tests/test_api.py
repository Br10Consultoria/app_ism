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


def test_nested_ixc_pix_qrcode():
    from app.ixc import extract_pix_code
    emv = "00020101021226800014br.gov.bcb.pix6304ABCD"
    response = {
        "gateway": {"gatewayNome": "TESTE"},
        "pix": {
            "dadosPix": {"devedor": {}, "valor": "89.90"},
            "qrCode": {"imagemQrcode": "base64image", "qrcode": emv, "imagemSrc": ""}
        },
        "type": "pix",
    }
    assert extract_pix_code(response) == emv
    assert extract_pix_code({"pix": {"qrCode": {"imagemQrcode": "base64image", "qrcode": "txid-123"}}}) == ""
    assert extract_pix_code({"pix_copia_cola": emv}) == emv


def test_pdf_base64_response_without_html():
    import base64
    from app.ixc import pdf_base64_from_response
    pdf = b"%PDF-1.4\n%test fixture\n%%EOF"
    encoded = base64.b64encode(pdf)
    assert pdf_base64_from_response(encoded) == encoded.decode("ascii")
    assert pdf_base64_from_response(encoded + b"\n") == encoded.decode("ascii")
    assert pdf_base64_from_response(pdf) == encoded.decode("ascii")
    assert pdf_base64_from_response(b"data:application/pdf;base64," + encoded) == encoded.decode("ascii")
    assert pdf_base64_from_response(b"<html>JVBERi0=</html>") == ""
    assert pdf_base64_from_response(base64.b64encode(b"not a pdf")) == ""


def test_boleto_post_and_mime_base64(monkeypatch):
    import asyncio
    import base64
    import httpx
    from app.ixc import IXC

    pdf = b"%PDF-1.4\n%test fixture\n%%EOF"
    payload = base64.b64encode(pdf)
    monkeypatch.setenv("IXC_URL", "https://ixc.example.test")
    monkeypatch.setenv("IXC_USER_ID", "test")
    monkeypatch.setenv("IXC_TOKEN", "test")

    class FakeClient:
        def __init__(self, **kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            pass

        async def request(self, method, url, json, headers):
            assert method == "POST"
            assert url.endswith("/webservice/v1/get_boleto")
            assert "ixcsoft" not in headers
            assert json["boletos"] == "123"
            assert json["tipo_boleto"] == "arquivo"
            assert json["base64"] == "S"
            return httpx.Response(
                200, request=httpx.Request(method, url),
                headers={"Content-Type": "text/html; charset=ISO-8859-1"},
                content=payload
            )

    monkeypatch.setattr(httpx, "AsyncClient", FakeClient)
    result = asyncio.run(IXC().payment({"id": "123"}, "boleto"))
    assert result["pdf_base64"] == payload.decode("ascii")
    assert result["url"] == ""


def test_cnpj_document():
    from app.ixc import valid_cnpj, valid_document, format_document
    cnpj = "11.222.333/0001-81"
    assert valid_cnpj(cnpj)
    assert valid_document(cnpj)
    assert format_document(cnpj) == cnpj
    assert not valid_cnpj("11.222.333/0001-82")
    assert valid_document("52998224725")


def test_lead_optional_customer_id():
    from app.main import Lead
    obj = Lead(cpf="52998224725", telefone="71999998888", nome="Teste",
               id_cliente="123", tecnico="Tecnico", consentimento=True)
    assert obj.id_cliente == "123"
