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


def test_no_receiver_saves_contact_as_pending_not_success(monkeypatch, tmp_path):
    for name in ("COMMERCIAL_WEBHOOK_URL", "SMTP_HOST", "COMMERCIAL_TO",
                 "OPA_TEMPLATE_LEAD_ID", "OPA_COMMERCIAL_PHONE"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("LEADS_DB", str(tmp_path / "leads.db"))
    payload = {
        "nome": "Cliente Teste", "cpf": "52998224725",
        "telefone": "71999998888", "tecnico": "Técnico A",
        "consentimento": True
    }
    r = client.post("/api/v1/interessados", headers=headers, json=payload)
    assert r.status_code == 200, r.text
    assert r.json()["enviado"] is False
    assert r.json()["situacao"] == "pendente"
    assert len(r.json()["protocolo"]) == 12
    assert "52998224725" not in r.text
    assert "71999998888" not in r.text

    from app.commercial_leads import list_leads
    saved = list_leads("pendente")
    assert len(saved) == 1
    assert saved[0]["cpf"] == payload["cpf"]
    assert saved[0]["telefone"] == payload["telefone"]

    # Reenvio acidental em menos de 1h usa o mesmo protocolo e não duplica.
    again = client.post("/api/v1/interessados", headers=headers, json=payload)
    assert again.status_code == 200
    assert again.json()["protocolo"] == r.json()["protocolo"]
    assert len(list_leads("pendente")) == 1


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


def test_pix_confirmation_strict_state_checks():
    from app.pix_confirmation import verify_pix_receipt
    invoice = {
        "id": "17", "id_cliente": "8", "status": "R",
        "recebido_via_pix": "S", "aguardando_confirmacao_pagamento": "N",
        "estornado": "N", "valor_recebido": "89.90",
        "valor_aberto": "0.00", "pagamento_data": "2026-11-06",
        "documento": "BOL-17", "pix_txid": "tx-17"
    }
    result = verify_pix_receipt(invoice)
    assert result["confirmado"] is True
    assert result["situacao"] == "confirmado"
    assert result["valor_recebido"] == "89.90"
    assert "id_cliente" not in result

    cases = [
        ({"status": "A"}, "aguardando"),
        ({"status": "R", "recebido_via_pix": "N"}, "outro_meio"),
        ({"status": "R", "recebido_via_pix": ""}, "indeterminado"),
        ({"aguardando_confirmacao_pagamento": "S"}, "aguardando"),
        ({"estornado": "S"}, "estornado"),
        ({"valor_aberto": "12.30"}, "parcial"),
        ({"valor_recebido": ""}, "indeterminado"),
        ({"status": "C"}, "cancelado"),
    ]
    for update, state in cases:
        test = {**invoice, **update}
        response = verify_pix_receipt(test)
        assert response["confirmado"] is False
        assert response["situacao"] == state


def test_pix_confirmation_endpoint_scope_and_auth(monkeypatch):
    from app import main as module

    class FakeIXC:
        async def invoice(self, ident):
            return {"id": str(ident), "id_cliente": "123",
                    "status": "R", "liberado": "S", "recebido_via_pix": "S",
                    "valor_recebido": "89.90", "valor_aberto": "0",
                    "estornado": "N", "aguardando_confirmacao_pagamento": "N"}

    monkeypatch.setattr(module, "IXC", FakeIXC)
    url = "/api/v1/faturas/99/confirmar-pix"
    good = {"session": create_session("123")}
    forbidden = {"session": create_session("999")}
    assert client.post(url, json=good).status_code == 401
    assert client.post(url, json=forbidden, headers=headers).status_code == 404
    ok = client.post(url, json=good, headers=headers)
    assert ok.status_code == 200
    assert ok.json()["confirmado"] is True
    assert ok.json()["id"] == "99"
    assert ok.json()["origem"] == "IXC"
    assert "verificado_em" in ok.json()
    assert "cpf" not in ok.json()


def test_pix_confirmation_unknown_not_claimed_paid(monkeypatch):
    from app import main as module

    class FakeIXC:
        async def invoice(self, ident):
            return {"id": str(ident), "id_cliente": "123",
                    "status": "A", "recebido_via_pix": "N"}

    monkeypatch.setattr(module, "IXC", FakeIXC)
    r = client.post("/api/v1/faturas/17/confirmar-pix",
                    headers=headers, json={"session": create_session("123")})
    assert r.status_code == 200
    assert r.json() == {"situacao": "aguardando", "confirmado": False}



def test_recent_pix_received_requires_auth_scope_and_checks_status(monkeypatch):
    from app import main as module

    class FakeIXC:
        async def recent_customer_invoices(self, customer_id):
            assert customer_id == "123"
            return [
                {"id": "218848", "id_cliente": "123", "status": "R",
                 "recebido_via_pix": "S", "valor_recebido": "0.10", "valor_aberto": "0.00",
                 "aguardando_confirmacao_pagamento": "N", "estornado": "N",
                 "documento": "T-10", "data_pagamento": "2026-11-06"},
                {"id": "295590", "id_cliente": "123", "status": "R",
                 "recebido_via_pix": "N", "valor_recebido": "179.80", "valor_aberto": "0.00"},
                {"id": "30", "id_cliente": "999", "status": "R",
                 "recebido_via_pix": "S", "valor_recebido": "10.00", "valor_aberto": "0.00"},
                {"id": "40", "id_cliente": "123", "status": "A",
                 "recebido_via_pix": "N"},
            ]

    monkeypatch.setattr(module, "IXC", FakeIXC)
    url = "/api/v1/faturas/pix-recebidos"
    session = {"session": create_session("123")}
    assert client.post(url, json=session).status_code == 401
    assert client.post(url, headers=headers, json={"session": "invalid"}).status_code == 422
    response = client.post(url, headers=headers, json=session)
    assert response.status_code == 200
    assert response.json()["limite_consulta"] == 100
    assert response.json()["pix_recebidos"] == [{
        "id": "218848", "valor_recebido": "0.10", "data_pagamento": "2026-11-06",
        "documento": "T-10"
    }]
    assert "cpf" not in response.text
    assert "295590" not in response.text
    assert "999" not in response.text


def test_recent_invoices_query_descending(monkeypatch):
    import asyncio
    from app.ixc import IXC

    async def fake_list(self, endpoint, field, value, limit, **kwargs):
        assert endpoint == "fn_areceber"
        assert field == "fn_areceber.id_cliente"
        assert value == "123"
        assert limit == 100
        assert kwargs["sortname"] == "fn_areceber.id"
        assert kwargs["sortorder"] == "desc"
        return []

    monkeypatch.setattr(IXC, "list_records", fake_list)
    monkeypatch.setenv("IXC_URL", "https://ixc.example.test")
    monkeypatch.setenv("IXC_USER_ID", "test")
    monkeypatch.setenv("IXC_TOKEN", "test")
    assert asyncio.run(IXC().recent_customer_invoices("123")) == []



def test_backend_version_and_short_session_ttl():
    import base64
    import json
    import time
    version = client.get("/version")
    assert version.status_code == 200
    assert version.json() == {
        "app": "ISM Telecom", "versao": "0.12.9", "sessao_segundos": 300
    }

    token = create_session("123")
    encoded = token.split(".", 1)[0]
    payload = json.loads(base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)))
    assert payload["id"] == "123"
    assert 299 <= payload["exp"] - int(time.time()) <= 301
    assert read_session(token) == "123"


def test_customer_name_and_cpf_returned_only_after_valid_lookup(monkeypatch):
    from app import main as module

    class FakeIXC:
        async def customer(self, cpf):
            assert cpf == "52998224725"
            return {"id": "123", "razao": "Cliente Homologacao",
                    "cnpj_cpf": "529.982.247-25", "senha": "nao-expor",
                    "endereco": "nao-expor"}
        async def open_invoices(self, client_id):
            assert client_id == "123"
            return []

    monkeypatch.setattr(module, "IXC", FakeIXC)
    payload = {"cpf": "52998224725"}
    assert client.post("/api/v1/faturas", json=payload).status_code == 401
    response = client.post("/api/v1/faturas", headers=headers, json=payload)
    assert response.status_code == 200
    assert response.json()["cliente"] == {
        "nome": "Cliente Homologacao", "cpf": "529.982.247-25"
    }
    assert response.json()["faturas"] == []
    assert read_session(response.json()["session"]) == "123"
    assert "nao-expor" not in response.text


def test_session_explicit_renewal_and_auth_required():
    url = "/api/v1/sessao/renovar"
    original = create_session("123")
    assert client.post(url, json={"session": original}).status_code == 401
    response = client.post(url, headers=headers, json={"session": original})
    assert response.status_code == 200
    assert response.json()["sessao_segundos"] == 300
    assert read_session(response.json()["session"]) == "123"
    assert client.post(url, headers=headers, json={"session": "not-a-signed-token"}).status_code in (401, 422)


def test_session_renewal_rejects_expired_token():
    import base64
    import hashlib
    import hmac
    import json
    import time
    from app.main import secret
    payload = {"id": "123", "exp": int(time.time()) - 180}
    encoded = base64.urlsafe_b64encode(
        json.dumps(payload, separators=(",", ":")).encode()).decode().rstrip("=")
    signature = hmac.new(secret(), encoded.encode(), hashlib.sha256).hexdigest()
    expired = encoded + "." + signature
    response = client.post("/api/v1/sessao/renovar",
                           headers=headers, json={"session": expired})
    assert response.status_code == 401


def test_customer_name_fallback_uses_ixc_nome(monkeypatch):
    from app import main as module

    class FakeIXC:
        async def customer(self, document):
            return {"id": "123", "razao": "", "nome": "Cliente IXC"}
        async def open_invoices(self, client_id):
            return []

    monkeypatch.setattr(module, "IXC", FakeIXC)
    response = client.post("/api/v1/faturas",
                           headers=headers, json={"cpf": "52998224725"})
    assert response.status_code == 200
    assert response.json()["cliente"]["nome"] == "Cliente IXC"



def test_terminal_activation_validates_key_without_sensitive_data():
    good = client.post("/api/v1/terminal/validar", headers=headers, json={})
    assert good.status_code == 200
    assert good.json() == {"autorizado": True}
    denied = client.post("/api/v1/terminal/validar",
                         headers={"X-Terminal-Key": "invalid"}, json={})
    assert denied.status_code == 401
    assert "terminal" not in good.text.lower() or "autorizado" in good.text.lower()
