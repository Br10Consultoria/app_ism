"""Integração read-only com o IXC. Confirme o formato de respostas em sua carteira."""
import base64
import binascii
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


def valid_cnpj(value):
    number = digits(value)
    if len(number) != 14 or len(set(number)) == 1:
        return False
    for size, weights in (
        (12, (5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)),
        (13, (6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)),
    ):
        rest = sum(int(n) * w for n, w in zip(number[:size], weights)) % 11
        expected = 0 if rest < 2 else 11 - rest
        if int(number[size]) != expected:
            return False
    return True


def valid_document(value):
    return valid_cpf(value) or valid_cnpj(value)


def format_document(value):
    number = digits(value)
    if len(number) == 14:
        return f"{number[:2]}.{number[2:5]}.{number[5:8]}/{number[8:12]}-{number[12:]}"
    return format_cpf(number)


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


def pdf_base64_from_response(content):
    """Aceita somente PDF binário ou PDF Base64 puro, mesmo com MIME text/html."""
    max_pdf_size = 8 * 1024 * 1024
    if not content or len(content) > (max_pdf_size * 4 // 3 + 8192):
        return ""
    raw = content.strip()
    if raw.startswith(b"%PDF-"):
        pdf = raw
    else:
        prefix = b"data:application/pdf;base64,"
        if raw.lower().startswith(prefix):
            raw = raw[len(prefix):]
        # O IXC pode devolver apenas Base64, com quebras de linha.
        raw = b"".join(raw.split())
        if not raw or len(raw) % 4:
            return ""
        try:
            pdf = base64.b64decode(raw, validate=True)
        except (binascii.Error, ValueError):
            return ""
    if not 5 <= len(pdf) <= max_pdf_size or not pdf.startswith(b"%PDF-"):
        return ""
    return base64.b64encode(pdf).decode("ascii")


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

    async def request(self, path, body, raw=False, method="GET", include_ixcsoft=True):
        # Nunca aceitar URL/endpoint do cliente: evita SSRF.
        if path not in ("cliente", "fn_areceber", "get_pix", "get_boleto"):
            raise IXCError("Endpoint IXC não permitido")
        if method not in ("GET", "POST"):
            raise IXCError("Método IXC não permitido")
        headers = {
            "Authorization": self.auth,
            "Content-Type": "application/json",
            "Accept": "application/json, application/pdf",
        }
        if include_ixcsoft:
            headers["ixcsoft"] = "listar"
        try:
            async with httpx.AsyncClient(timeout=30, follow_redirects=False, trust_env=False) as client:
                response = await client.request(
                    method, f"{self.root}/{path}", json=body, headers=headers
                )
            response.raise_for_status()
            if raw:
                encoded = pdf_base64_from_response(response.content)
                if encoded:
                    return {"pdf_base64": encoded}
                if not response.content:
                    raise IXCError("IXC retornou boleto vazio")
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
        for query in (format_document(cpf), digits(cpf)):
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
            # Na instalação ISM, get_boleto responde Base64 puro em text/html.
            # Endpoint de ação do IXC: POST sem o cabeçalho ixcsoft.
            data = await self.request("get_boleto", {
                "boletos": str(invoice["id"]),
                "tipo_boleto": "arquivo",
                "base64": "S",
                "qjuro": "N",
                "juro": "N",
                "multa": "N",
                "atualiza_boleto": "N",
            }, raw=True, method="POST", include_ixcsoft=False)
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
                except (ValueError, binascii.Error):
                    pdf = b""
                if not pdf.startswith(b"%PDF-") or len(pdf) > 8 * 1024 * 1024:
                    encoded = ""
            if url and not url.startswith("https://"):
                url = ""
            if not encoded and not url:
                raise IXCError("Boleto não disponibilizado pelo IXC")
            return {"codigo": "", "url": url, "pdf_base64": encoded}
        raise IXCError("Meio de pagamento inválido")
