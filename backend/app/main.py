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
import sqlite3
import time
import uuid
from threading import Lock
from urllib.parse import urlparse

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from .ixc import IXC, IXCError, digits, valid_cpf, valid_document, format_document, pick
from .pix_confirmation import verify_pix_receipt
from .terminal_activation import redeem, ActivationError
from .boleto_email import registered_email, mask_email, reserve_ixc_send, BoletoEmailError
from .commercial_leads import save_interest, claim_for_send, set_status
from . import whatsapp_opa
from .whatsapp_provider import (
    WhatsAppError, customer_phone, phone_mask, first_name, new_customer,
    configured as whatsapp_configured, send_once
)

app = FastAPI(title="ISM Telecom — API do Terminal", docs_url=None, redoc_url=None)
rate_lock = Lock()
attempts = defaultdict(deque)


class QRActivationRequest(BaseModel):
    codigo: str = Field(min_length=43, max_length=43, pattern=r"^[A-Za-z0-9_-]{43}$")


class Lookup(BaseModel):
    cpf: str = Field(min_length=11, max_length=18)


class SessionRequest(BaseModel):
    session: str = Field(min_length=15, max_length=1500)


class BoletoEmailRequest(SessionRequest):
    confirmar_envio: bool = False


class WhatsAppRequest(SessionRequest):
    consentimento: bool


class WelcomeRequest(WhatsAppRequest):
    confirma_novo_cliente: bool


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
    payload = {"id": str(customer_id), "exp": int(time.time()) + 300}
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


@app.post("/api/v1/terminal/ativar-qr", dependencies=[Depends(throttle)])
async def ativar_qr(body: QRActivationRequest):
    """Troca convite de uso único por uma chave protegida, sempre via TLS."""
    key = os.getenv("TERMINAL_KEY", "")
    if not 32 <= len(key) <= 512:
        raise HTTPException(503, "Ativação ainda não configurada no servidor")
    try:
        if not redeem(body.codigo):
            raise HTTPException(401, "QR inválido, expirado ou já utilizado. Solicite outro.")
    except (ActivationError, OSError, sqlite3.Error):
        raise HTTPException(503, "Serviço de ativação indisponível")
    return JSONResponse(
        {"autorizado": True, "terminal_key": key},
        headers={"Cache-Control": "no-store, private", "Pragma": "no-cache"},
    )


@app.post("/api/v1/terminal/validar",
          dependencies=[Depends(require_terminal), Depends(throttle)])
async def validar_terminal():
    """Valida a chave do terminal sem buscar nem expor dados de assinantes."""
    return {"autorizado": True}


@app.get("/version")
async def version():
    """Permite verificar se backend e Android estão na mesma versão."""
    return {"app": "ISM Telecom", "versao": "0.11.6", "sessao_segundos": 300}


@app.post("/api/v1/sessao/renovar",
          dependencies=[Depends(require_terminal), Depends(throttle)])
async def renovar_sessao(body: SessionRequest):
    """Renova por mais 300s somente mediante acao do operador e token ainda valido."""
    client_id = read_session(body.session)
    return {"session": create_session(client_id), "sessao_segundos": 300}


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
        # Somente dados do cliente que teve o documento validado na consulta.
        # Nunca retornar os campos bancarios, tokens ou endereco do cadastro IXC.
        customer_name = pick(client, ("razao", "nome", "nome_razao", "fantasia"))
        cellphone = customer_phone(client)
        email = registered_email(client)
        return {"session": create_session(client_id),
                "cliente": {
                    "nome": customer_name[:100] or "Nome não informado pelo IXC",
                    "cpf": format_document(body.cpf)
                },
                "boleto_email": {
                    "cadastrado": bool(email),
                    "disponivel": bool(email),
                    "destinatario_mascarado": mask_email(email) if email else ""
                },
                "whatsapp": {
                    "destinatario_mascarado": phone_mask(cellphone),
                    "boas_vindas_disponivel": bool(
                        cellphone and new_customer(client) and whatsapp_configured("boas_vindas")
                    ),
                    "confirmacao_pix_disponivel": bool(
                        cellphone and whatsapp_configured("pix")
                    )
                },
                "faturas": [make_invoice(row) for row in invoices],
                "mensagem": "" if invoices else "Nenhuma cobrança em aberto"}
    except IXCError:
        raise HTTPException(502, "Erro ao consultar cobranças no IXC")


@app.post("/api/v1/faturas/pix-recebidos",
          dependencies=[Depends(require_terminal), Depends(throttle)])
async def pix_recebidos(body: SessionRequest):
    """Somente leitura. Retorna ate 10 PIX confirmados dos 100 titulos mais recentes."""
    customer_id = read_session(body.session)
    try:
        ixc = IXC()
        rows = await ixc.recent_customer_invoices(customer_id)
        result = []
        for row in rows:
            if str(row.get("id_cliente")) != customer_id:
                continue
            if not str(row.get("id") or "").isdigit():
                continue
            receipt = verify_pix_receipt(row)
            if not receipt["confirmado"]:
                continue
            result.append({
                "id": receipt["id"],
                "valor_recebido": receipt["valor_recebido"],
                "data_pagamento": receipt["data_pagamento"],
                "documento": receipt["documento"],
            })
            if len(result) == 10:
                break
        return {"pix_recebidos": result, "limite_consulta": 100}
    except IXCError:
        raise HTTPException(502, "Nao foi possivel consultar recebimentos PIX no IXC")


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



@app.post("/api/v1/whatsapp/boas-vindas",
          dependencies=[Depends(require_terminal), Depends(throttle)])
async def whatsapp_boas_vindas(body: WelcomeRequest):
    """Enviar apenas para cliente novo, ativo no IXC e com autorização confirmada."""
    if not body.consentimento or not body.confirma_novo_cliente:
        raise HTTPException(422, "Confirme novo cadastro e autorização do cliente")
    client_id = read_session(body.session)
    try:
        ixc = IXC()
        customer = await ixc.customer_by_id(client_id)
        if not customer or str(customer.get("id")) != client_id:
            raise HTTPException(404, "Cliente não localizado no IXC")
        if not new_customer(customer):
            raise HTTPException(409, "Boas-vindas reservadas a novos clientes cadastrados no IXC")
        phone = customer_phone(customer)
        if not phone:
            raise HTTPException(422, "Cliente sem WhatsApp celular válido no IXC")
        result = await send_once(
            "boas_vindas", client_id, "", phone,
            [first_name(customer), client_id],
        )
        return {**result, "destinatario_mascarado": phone_mask(phone)}
    except IXCError:
        raise HTTPException(502, "Falha na consulta ao cadastro do cliente no IXC")
    except WhatsAppError as error:
        raise HTTPException(error.status_code, str(error))


@app.post("/api/v1/whatsapp/pix/{invoice_id}",
          dependencies=[Depends(require_terminal), Depends(throttle)])
async def whatsapp_pix(invoice_id: int, body: WhatsAppRequest):
    """Template informativo sobre PIX realmente baixado; nunca envia por estado presumido."""
    if invoice_id < 1:
        raise HTTPException(404, "Fatura inválida")
    if not body.consentimento:
        raise HTTPException(422, "É necessário confirmar autorização do cliente para o WhatsApp")
    client_id = read_session(body.session)
    try:
        ixc = IXC()
        invoice = await ixc.invoice(invoice_id)
        if not invoice or str(invoice.get("id_cliente")) != client_id:
            raise HTTPException(404, "Fatura não encontrada para este cliente")
        receipt = verify_pix_receipt(invoice)
        if not receipt["confirmado"]:
            raise HTTPException(409, "PIX ainda não confirmado no IXC; mensagem não enviada")

        customer = await ixc.customer_by_id(client_id)
        if not customer or str(customer.get("id")) != client_id:
            raise HTTPException(404, "Cliente não localizado no IXC")
        phone = customer_phone(customer)
        if not phone:
            raise HTTPException(422, "Cliente sem WhatsApp celular válido no IXC")

        payment_date = receipt.get("data_pagamento") or ""
        if re.match(r"^\d{4}-\d{2}-\d{2}", payment_date):
            payment_date = payment_date[8:10] + "/" + payment_date[5:7] + "/" + payment_date[:4]
        if not payment_date:
            payment_date = "data não informada pelo IXC"
        payment_date = payment_date[:40]

        result = await send_once(
            "pix", client_id, receipt["id"], phone,
            [first_name(customer), receipt["valor_recebido"], receipt["id"], payment_date]
        )
        return {**result, "destinatario_mascarado": phone_mask(phone)}
    except IXCError:
        raise HTTPException(502, "Falha ao consultar PIX ou cliente no IXC")
    except WhatsAppError as error:
        raise HTTPException(error.status_code, str(error))


@app.post("/api/v1/faturas/{invoice_id}/enviar-email",
          dependencies=[Depends(require_terminal), Depends(throttle)])
async def enviar_boleto_email(invoice_id: int, body: BoletoEmailRequest):
    """Envia pelo próprio IXC, usando o e-mail existente no cadastro e SMTP do ERP."""
    if invoice_id < 1:
        raise HTTPException(404, "Cobrança inválida")
    if not body.confirmar_envio:
        raise HTTPException(422, "Confirme o envio do boleto ao e-mail cadastrado no IXC")
    customer_id = read_session(body.session)
    try:
        ixc = IXC()
        invoice = await ixc.invoice(invoice_id)
        if not invoice or str(invoice.get("id_cliente")) != customer_id:
            raise HTTPException(404, "Cobrança não encontrada para este cliente")
        if invoice.get("status") != "A" or invoice.get("liberado") != "S":
            raise HTTPException(409, "Cobrança não está aberta")
        customer = await ixc.customer_by_id(customer_id)
        if not customer or str(customer.get("id")) != customer_id:
            raise HTTPException(404, "Cliente não encontrado no IXC")
        email = registered_email(customer)
        if not email:
            raise HTTPException(422, "Cliente sem e-mail único válido cadastrado no IXC")

        # Impede que toques duplos ou timeout gerem vários envios iguais.
        reserve_ixc_send(invoice_id)
        result = await ixc.send_boleto_email(invoice_id)
        return {
            **result,
            "destinatario_mascarado": mask_email(email),
            "mensagem": "Solicitação aceita pela API do IXC; a entrega não foi confirmada."
        }
    except IXCError:
        raise HTTPException(502, "IXC não confirmou a solicitação de e-mail; confira o log antes de tentar novamente")
    except BoletoEmailError as exc:
        raise HTTPException(exc.status, str(exc)) from exc


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




def prospect_whatsapp_configured():
    """Opa! Suite com template próprio e opt-in administrativo explícito."""
    return (
        os.getenv("WHATSAPP_PROVIDER", "").strip().lower() == "opa"
        and os.getenv("OPA_PROSPECT_WHATSAPP_ENABLED", "").strip().lower()
            in ("true", "1", "s", "sim")
        and whatsapp_opa.configured("prospecto")
    )


def commercial_configured(data=None):
    """Preferir WhatsApp do interessado, quando opt-in da empresa e número válidos."""
    if prospect_whatsapp_configured() and data is not None:
        number = digits(data.get("telefone", ""))
        if len(number) == 11 and number[2] == "9":
            return True
    opa_ready = (
        os.getenv("WHATSAPP_PROVIDER", "").lower() == "opa"
        and os.getenv("OPA_BASE_URL", "")
        and os.getenv("OPA_API_TOKEN", "")
        and os.getenv("OPA_CHANNEL_ID", "")
        and os.getenv("OPA_TEMPLATE_LEAD_ID", "")
        and os.getenv("OPA_COMMERCIAL_PHONE", "")
    )
    return bool(
        opa_ready
        or os.getenv("COMMERCIAL_WEBHOOK_URL", "")
        or (os.getenv("SMTP_HOST", "") and os.getenv("COMMERCIAL_TO", ""))
    )


async def send_commercial(data):
    """Conversa com interessado via Opa ou aviso interno via canais comerciais."""
    number = digits(data["telefone"])
    if prospect_whatsapp_configured() and len(number) == 11 and number[2] == "9":
        message_id = await whatsapp_opa.send_template(
            "prospecto", "55" + number,
            [data["nome"].split()[0], "ISM TELECOM"],
            open_customer_service=True,
        )
        return "opa_cliente", message_id

    if (os.getenv("WHATSAPP_PROVIDER", "").lower() == "opa"
            and os.getenv("OPA_TEMPLATE_LEAD_ID", "")
            and os.getenv("OPA_COMMERCIAL_PHONE", "")):
        # Telefone do departamento comercial cadastrado pelo administrador.
        destination = digits(os.environ["OPA_COMMERCIAL_PHONE"])
        if len(destination) == 11 and destination[2] == "9":
            destination = "55" + destination
        if len(destination) != 13 or not destination.startswith("55") or destination[4] != "9":
            raise ValueError("OPA_COMMERCIAL_PHONE inválido")
        message_id = await whatsapp_opa.send_template("interessados", destination, [
            data["nome"], data["telefone"], data["tecnico"], data["protocolo"]
        ])
        return "opa", message_id

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
        return "webhook", ""

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
        return "email", ""
    raise ValueError("Canal comercial não configurado")


def lead_response(protocol, state, channel=""):
    if state == "encaminhado" and channel == "opa_cliente":
        return {
            "enviado": True, "situacao": "mensagem_cliente", "protocolo": protocol,
            "mensagem": "Opa! Suite aceitou a solicitação de mensagem ao interessado "
                        "e abertura de atendimento. A entrega e o direcionamento "
                        "ao comercial devem ser verificados no Opa! Suite."
        }
    if state == "encaminhado":
        return {"enviado": True, "situacao": "encaminhado", "protocolo": protocol,
                "mensagem": "Solicitação registrada e aceita pelo canal comercial."}
    if state == "revisar":
        return {"enviado": False, "situacao": "revisar", "protocolo": protocol,
                "mensagem": "Contato registrado. Confirme o encaminhamento antes de repetir."}
    return {"enviado": False, "situacao": "pendente", "protocolo": protocol,
            "mensagem": "Contato registrado. Encaminhamento ao comercial pendente."}


async def try_commercial_delivery(data):
    """Reutilizado pelo terminal e pelo comando administrativo de reenfileiramento."""
    protocol = data["protocolo"]
    if not commercial_configured(data):
        return lead_response(protocol, "pendente")
    if not claim_for_send(protocol):
        return lead_response(protocol, "pendente")
    try:
        channel, reference = await send_commercial(data)
    except (ValueError, OSError, smtplib.SMTPException, httpx.HTTPError,
            whatsapp_opa.WhatsAppError):
        # Timeouts e 5xx podem ter sido aceitos pelo destino. Não repetir no automático.
        set_status(protocol, "revisar", detail="Conferir histórico do canal comercial")
        return lead_response(protocol, "revisar")
    set_status(protocol, "encaminhado", channel=channel, reference=reference)
    return lead_response(protocol, "encaminhado", channel=channel)


@app.post("/api/v1/interessados", dependencies=[Depends(require_terminal), Depends(throttle)])
async def interessados(body: Lead):
    phone = digits(body.telefone)
    if not valid_cpf(body.cpf) or len(phone) not in (10, 11):
        raise HTTPException(422, "CPF ou telefone inválido")
    if not body.consentimento:
        raise HTTPException(422, "É necessário consentimento para contato")
    if len(body.nome.strip()) < 2 or len(body.tecnico.strip()) < 2:
        raise HTTPException(422, "Informe o nome do interessado e do técnico")

    data = {
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
        saved = save_interest(data)
    except (OSError, ValueError, sqlite3.Error):
        raise HTTPException(503, "Falha ao registrar o interessado; tente novamente")
    data["protocolo"] = saved["protocolo"]
    if not saved["novo"]:
        return lead_response(data["protocolo"], saved["estado"],
                             channel=saved.get("canal", ""))
    return await try_commercial_delivery(data)
