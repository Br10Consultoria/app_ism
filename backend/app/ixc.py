"""Integração read-only com o IXC. Confirme o formato de respostas em sua carteira."""
import base64
import json
import os
import re
from urllib.parse import urlparse

import httpx


class IXCError(Exception):
    pass


def digits(value):
    return re.sub(r"\D", "", str(value or ""))


def format_cpf(cpf):
    d = digits(cpf)
    return f"{d[:3]}.{d[3:6]}.{d[6:9]}-{d[9:]}"


def valid_cpf(cpf):
    d = digits(cpf)
    if len(d) != 11 or len(set(d)) == 1:
        return False
    for length in (9, 10):
        number = (sum(int(n) * (length + 1 - i) for i, n in enumerate(d[:length])) * 10) % 11
        if (0 if number == 10 else number) != int(d[length]):
            return False
    return True


def pick(source, names):
    if not isinstance(source, dict):
        return ""
    for name in names:
        value = source.get(name)
        if isinstance(value, (str, int, float)) and str(value).strip():
            return str(value).strip()
    return ""


def extract_pix_code(data):
    """Seleciona apenas texto EMV do PIX; não usa txid nem a imagem QR."""
    candidates = [data]
    if isinstance(data, dict):
        for key in ("dados", "pix", "qrCode", "qrcode"):
            value = data.get(key)
            if isinstance(value, dict):
                candidates.append(value)
                if key == "pix":
                    qr = value.get("qrCode")
                    if isinstance(qr, dict):
                        candidates.append(qr)
    fields = ("pix_copia_cola", "pix_copia_e_cola", "copia_cola",
              "pix_code", "qrcode_text", "qr_code", "qrcode", "emv", "pix")
    for obj in candidates:
        code = pick(obj, fields)
        if code.strip().upper().startswith("000201") and len(code) <= 1500:
            return code.strip()
    return ""


class IXC:
    def __init__(self):
        raw_url = os.getenv("IXC_URL", "").rstrip("/")
        parsed = urlparse(raw_url)
        if parsed.scheme != "https" or not parsed.netloc or parsed.username or parsed.password:
            raise IXCError("Configure IXC_URL como URL HTTPS válida")
        self.root = raw_url.removesuffix("/webservice/v1") + "/webservice/v1"
        raw_basic = os.getenv("IXC_BASIC_AUTH", "").strip()
        if raw_basic:
            if not raw_basic.startswith("Basic "):
                raise IXCError("IXC_BASIC_AUTH deve começar com Basic")
            self.auth = raw_basic
        else:
            user = os.getenv("IXC_USER_ID", "")
            token = os.getenv("IXC_TOKEN", "")
            if not user or not token:
                raise IXCError("Configure IXC_USER_ID e IXC_TOKEN")
            self.auth = "Basic " + base64.b64encode(f"{user}:{token}".encode()).decode()

    async def request(self, path, body, raw=False):
        # Nunca aceitar URL/endpoint do cliente: evita SSRF.
        if path not in ("cliente", "fn_areceber", "get_pix", "get_boleto"):
            raise IXCError("Endpoint IXC não permitido")
        try:
            async with httpx.AsyncClient(timeout=15, follow_redirects=False, trust_env=False) as client:
                response = await client.request(
                    "GET", f"{self.root}/{path}", json=body,
                    headers={"Authorization": self.auth, "ixcsoft": "listar", "Accept": "application/json, application/pdf"}
                )
            response.raise_for_status()
            if raw and response.content.startswith(b"%PDF-"):
                return {"pdf_base64": base64.b64encode(response.content).decode()}
            value = response.json()
            if not isinstance(value, (dict, list)):
                raise IXCError("Retorno IXC inesperado")
            return value
        except (httpx.HTTPError, ValueError) as exc:
            raise IXCError("IXC indisponível ou retornou formato inválido") from exc

    async def list_records(self, endpoint, field, value, limit=50):
        # Alguns servidores IXC exigem GET com corpo JSON.
        result = await self.request(endpoint, {
            "qtype": field, "query": str(value), "oper": "=",
            "page": "1", "rp": str(limit),
            "sortname": "fn_areceber.data_vencimento" if endpoint == "fn_areceber" else "cliente.id",
            "sortorder": "asc",
        })
        if not isinstance(result, dict):
            raise IXCError("IXC: lista inválida")
        records = result.get("registros") or []
        if not isinstance(records, list):
            raise IXCError("IXC: registros inválidos")
        return records

    async def customer(self, cpf):
        # IXC normalmente armazena CPF pontuado. Tenta ambos sem usar busca parcial.
        for query in (format_cpf(cpf), digits(cpf)):
            found = await self.list_records("cliente", "cliente.cnpj_cpf", query, 5)
            for row in found:
                if digits(row.get("cnpj_cpf")) == digits(cpf):
                    return row
        return None

    async def open_invoices(self, customer_id):
        found = await self.list_records("fn_areceber", "fn_areceber.id_cliente", customer_id, 100)
        result = []
        for row in found:
            if str(row.get("id_cliente")) != str(customer_id):
                continue
            if row.get("status") != "A" or row.get("liberado") != "S":
                continue
            result.append(row)
        return result

    async def invoice(self, id_):
        found = await self.list_records("fn_areceber", "fn_areceber.id", id_, 1)
        for row in found:
            if str(row.get("id")) == str(id_):
                return row
        return None

    async def payment(self, invoice, kind):
        if kind == "pix":
            # IXC get_pix: id_areceber é o campo utilizado em instalações conhecidas.
            data = await self.request("get_pix", {"id_areceber": str(invoice["id"])})
            if isinstance(data, dict) and isinstance(data.get("registros"), list) and data["registros"]:
                data = data["registros"][0]
            if isinstance(data, dict) and isinstance(data.get("dados"), dict):
                data = data["dados"]
            code = extract_pix_code(data)
            if not code:
                raise IXCError("Esta cobrança não disponibilizou Pix copia e cola no IXC")
            return {"codigo": code, "url": "", "pdf_base64": ""}
        if kind == "barra":
            code = pick(invoice, ("linha_digitavel", "linha_dig", "codigo_barras", "cod_barras"))
            code = digits(code)
            if len(code) not in (44, 47, 48):
                # Algumas carteiras só devolvem a linha no endpoint de boleto.
                boleto = await self.request("get_boleto", {
                    "id": str(invoice["id"]), "base64": "N", "juros": "N", "multa": "N"
                })
                if isinstance(boleto, list) and boleto:
                    boleto = boleto[0]
                if isinstance(boleto, dict) and isinstance(boleto.get("dados"), dict):
                    boleto = boleto["dados"]
                code = digits(pick(boleto, ("linha_digitavel", "linhaDigitavel",
                                            "linha_dig", "codigo_barras", "barcode")))
            if len(code) not in (44, 47, 48):
                raise IXCError("Linha digitável indisponível nesta cobrança")
            return {"codigo": code, "url": "", "pdf_base64": ""}
        if kind == "boleto":
            link = pick(invoice, ("gateway_link", "boleto_url", "link_boleto", "url_boleto"))
            # Somente links seguros; pode exigir login IXC no dispositivo.
            if link.startswith("https://"):
                return {"codigo": "", "url": link, "pdf_base64": ""}
            data = await self.request("get_boleto", {
                "id": str(invoice["id"]), "base64": "S", "juros": "N", "multa": "N"
            }, raw=True)
            if isinstance(data, list) and data:
                data = data[0]
            if not isinstance(data, dict):
                raise IXCError("Resposta de boleto inválida")
            encoded = pick(data, ("pdf_base64", "pdfBase64", "base64", "pdf", "arquivo"))
            url = pick(data, ("pdf_url", "pdfUrl", "url", "link"))
            if encoded.startswith("data:application/pdf;base64,"):
                encoded = encoded.split(",", 1)[1]
            if encoded:
                try:
                    pdf = base64.b64decode(encoded, validate=True)
                except ValueError:
                    pdf = b""
                if not pdf.startswith(b"%PDF-") or len(pdf) > 8 * 1024 * 1024:
                    encoded = ""
            if url and not url.startswith("https://"):
                url = ""
            if not encoded and not url:
                raise IXCError("Boleto não disponibilizado pelo IXC")
            return {"codigo": "", "url": url, "pdf_base64": encoded}
        raise IXCError("Meio de pagamento inválido")
