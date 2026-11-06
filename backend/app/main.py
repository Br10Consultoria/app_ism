"""API controlada para terminais ISM. Não expor diretamente à Internet sem TLS e controles."""
import asyncio
import base64
from collections import defaultdict, deque
from datetime import datetime, timezone
from email.message import EmailMessage
import hashlib
import hmac
import json
import os
import re
import smtplib
import time
import uuid
from threading import Lock
from urllib.parse import urlparse

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from pydantic import BaseModel, Field

from .ixc import IXC, IXCError, digits, valid_cpf, valid_document
from .pix_confirmation import verify_pix_receipt

app = FastAPI(title="ISM Telecom — API do Terminal", docs_url=None, redoc_url=None)
rate_lock = Lock()
attempts = defaultdict(deque)


class Lookup(BaseModel):
    cpf: str = Field(min_length=11, max_length=18)


class SessionRequest(BaseModel):
    session: str = Field(min_length=15, max_length=1500)


class Lead(BaseModel):
    cpf: str = Field(min_length=11, max_length=18)
    telefone: str = Field(min_length=10, max_length=22)
    nome: str = Field(default="", max_length=100)
    id_cliente: str = Field(default="", max_length=40)
    tecnico: str = Field(min_length=2, max_length=80)
    consentimento: bool


def require_terminal(x_terminal_key: str = Header(default="")):
    configured = os.getenv("TERMINAL_KEY", "")
    if len(configured) < 32:
        raise HTTPException(503, "Chave de terminal não configurada")
    if not hmac.compare_digest(configured.encode(), x_terminal_key.encode()):
        raise HTTPException(401, "Terminal não autorizado")


def throttle(request: Request):
    # Controle simples por processo. Configure também rate limit no proxy e firewall.
    ip = request.client.host if request.client else "unknown"
    now = time.monotonic()
    with rate_lock:
        recent = attempts[ip]
        while recent and recent[0] < now - 60:
            recent.popleft()
        if len(recent) >= 30:
            raise HTTPException(429, "Limite de consultas atingido")
        recent.append(now)


def secret():
    value = os.getenv("SESSION_SECRET", "")
    if len(value) < 32:
        raise HTTPException(503, "Sessões não configuradas")
    return value.encode()


def create_session(customer_id):
    payload = {"id": str(customer_id), "exp": int(time.time()) + 180}
    encoded = base64.urlsafe_b64encode(json.dumps(payload, separators=(",", ":")).encode()).decode().rstrip("=")
    signature = hmac.new(secret(), encoded.encode(), hashlib.sha256).hexdigest()
    return encoded + "." + signature


def read_session(token):
    try:
        encoded, signature = token.split(".")
        actual = hmac.new(secret(), encoded.encode(), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(actual, signature):
            raise ValueError()
        raw = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))
        payload = json.loads(raw)
        if not isinstance(payload.get("id"), str) or not payload["id"].isdigit():
            raise ValueError()
        if int(payload.get("exp", 0)) < int(time.time()):
            raise ValueError()
        return payload["id"]
    except (ValueError, KeyError, TypeError, OverflowError):
        raise HTTPException(401, "Consulta expirada. Digite o CPF novamente.")


def make_invoice(row):
    return {"id": str(row["id"]),
            "vencimento": str(row.get("data_vencimento") or ""),
            "valor": str(row.get("valor_aberto") or row.get("valor") or "0"),
            "documento": str(row.get("documento") or "")}


@app.get("/health")
async def health():
    return {"status": "ok"}


@app.post("/api/v1/faturas", dependencies=[Depends(require_terminal), Depends(throttle)])
async def faturas(body: Lookup):
    if not valid_document(body.cpf):
        raise HTTPException(422, "CPF ou CNPJ inválido")
    try:
        ixc = IXC()
        client = await ixc.customer(body.cpf)
        if not client:
            # Sem revelar dados de clientes não encontrados.
            return {"session": "", "faturas": [], "mensagem": "Nenhuma cobrança disponível"}
        client_id = str(client.get("id", ""))
        if not client_id.isdigit():
            raise IXCError("Identificador de cliente inválido")
        invoices = await ixc.open_invoices(client_id)
        return {"session": create_session(client_id),
                "faturas": [make_invoice(row) for row in invoices],
                "mensagem": "" if invoices else "Nenhuma cobrança em aberto"}
    except IXCError:
        raise HTTPException(502, "Erro ao consultar cobranças no IXC")


@app.post("/api/v1/faturas/{invoice_id}/confirmar-pix",
          dependencies=[Depends(require_terminal), Depends(throttle)])
async def confirmar_pix(invoice_id: int, body: SessionRequest):
    """Consulta read-only. Não efetua baixa, não cria comprovante bancário."""
    if invoice_id < 1:
        raise HTTPException(404, "Cobrança inválida")
    customer_id = read_session(body.session)
    try:
        ixc = IXC()
        invoice = await ixc.invoice(invoice_id)
        if not invoice or str(invoice.get("id_cliente")) != customer_id:
            raise HTTPException(404, "Cobrança não encontrada")
        result = verify_pix_receipt(invoice)
        if result["confirmado"]:
            result["verificado_em"] = datetime.now(timezone.utc).isoformat()
        return result
    except IXCError:
        # Não confundir falha de conexão com pagamento pendente ou confirmado.
        raise HTTPException(502, "Não foi possível consultar a baixa no IXC")


@app.post("/api/v1/faturas/{invoice_id}/{kind}", dependencies=[Depends(require_terminal), Depends(throttle)])
async def pagamento(invoice_id: int, kind: str, body: SessionRequest):
    if invoice_id < 1 or kind not in ("pix", "barra", "boleto"):
        raise HTTPException(404, "Cobrança ou opção inválida")
    client_id = read_session(body.session)
    try:
        ixc = IXC()
        invoice = await ixc.invoice(invoice_id)
        if not invoice or str(invoice.get("id_cliente")) != client_id:
            raise HTTPException(404, "Cobrança não encontrada")
        if invoice.get("status") != "A" or invoice.get("liberado") != "S":
            raise HTTPException(409, "Cobrança não está aberta")
        info = await ixc.payment(invoice, kind)
        return {"tipo": kind, "id": invoice_id, "valor": make_invoice(invoice)["valor"],
                "vencimento": make_invoice(invoice)["vencimento"], **info}
    except IXCError as err:
        # Erros sem PII nem token.
        raise HTTPException(422, str(err))




async def send_commercial(data):
    url = os.getenv("COMMERCIAL_WEBHOOK_URL", "").strip()
    if url:
        parsed = urlparse(url)
        if parsed.scheme != "https" or not parsed.hostname:
            raise ValueError("Webhook comercial exige HTTPS")
        token = os.getenv("COMMERCIAL_WEBHOOK_TOKEN", "")
        headers = {"Authorization": "Bearer " + token} if token else {}
        async with httpx.AsyncClient(timeout=12, follow_redirects=False, trust_env=False) as client:
            result = await client.post(url, json=data, headers=headers)
        result.raise_for_status()
        return
    host, target = os.getenv("SMTP_HOST", ""), os.getenv("COMMERCIAL_TO", "")
    if host and target:
        msg = EmailMessage()
        msg["From"] = os.getenv("SMTP_FROM") or os.getenv("SMTP_USER")
        msg["To"] = target
        msg["Subject"] = "ISM Telecom | Novo interessado em fibra"
        msg.set_content(
            "Novo contato captado por tecnico ISM.\n"
            f"Protocolo: {data['protocolo']}\n"
            f"Data: {data['data']}\n"
            f"Nome: {data['nome']}\n"
            f"CPF: {data['cpf']}\n"
            f"Telefone: {data['telefone']}\n"
            f"ID Cliente: {data['id_cliente']}\n"
            f"Tecnico: {data['tecnico']}\n"
            "Consentimento para contato: sim\n"
        )
        def send_mail():
            with smtplib.SMTP_SSL(host, int(os.getenv("SMTP_PORT", "465")), timeout=12) as smtp:
                smtp.login(os.getenv("SMTP_USER", ""), os.getenv("SMTP_PASSWORD", ""))
                smtp.send_message(msg)
        await asyncio.to_thread(send_mail)
        return
    raise ValueError("Canal comercial não configurado")


@app.post("/api/v1/interessados", dependencies=[Depends(require_terminal), Depends(throttle)])
async def interessados(body: Lead):
    phone = digits(body.telefone)
    if not valid_cpf(body.cpf) or len(phone) not in (10, 11):
        raise HTTPException(422, "CPF ou telefone inválido")
    if not body.consentimento:
        raise HTTPException(422, "É necessário consentimento para contato")
    data = {
        "protocolo": uuid.uuid4().hex[:12].upper(),
        "data": datetime.now(timezone.utc).isoformat(),
        "cpf": digits(body.cpf),
        "telefone": phone,
        "nome": body.nome.strip(),
        "id_cliente": body.id_cliente.strip(),
        "tecnico": body.tecnico.strip(),
        "origem": "App Gertec ISM",
        "consentimento": True,
    }
    try:
        await send_commercial(data)
    except (ValueError, OSError, smtplib.SMTPException, httpx.HTTPError):
        raise HTTPException(503, "Comercial indisponível; envie novamente mais tarde")
    # Não persiste CPF/telefone no terminal ou backend depois do encaminhamento.
    return {"enviado": True, "protocolo": data["protocolo"]}
