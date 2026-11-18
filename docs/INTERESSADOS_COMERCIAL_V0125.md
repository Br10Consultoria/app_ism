# ISM Telecom — envio do CPF e identificação do técnico ao comercial

## Diagnóstico em 18/11/2026

Na versão Android v0.12.4, o formulário `Quero contratar` já envia `cpf` e `tecnico` para `POST /api/v1/interessados`. O backend confere CPF e DDD, depois grava esses campos em `/data/leads.db`, tabela `interessados`. Os dados **não foram omitidos do cadastro**.

O problema era **o destino da mensagem**:

1. Modelo Opa interno de interessados tinha apenas quatro variáveis: nome, telefone, identificação do técnico, protocolo; faltava CPF.
2. Com `OPA_PROSPECT_WHATSAPP_ENABLED=true`, o backend priorizava WhatsApp **do cliente**, que envia somente nome e marca e abre atendimento, suprimindo o envio de resumo ao número interno, mesmo quando configurado.

## Correção backend v0.12.5

- **Priorizar canal comercial autorizado** quando houver configuração: Opa! Suite interno, ou webhook HTTPS, ou e-mail comercial.
- Template Opa interno existente mantém quatro variáveis, na mesma ordem: **nome, telefone, técnico + CPF no mesmo campo, protocolo**. Exemplo para o terceiro campo: `Técnico de campo | CPF: 52998224725`. Essa identificação só vai ao número `OPA_COMMERCIAL_PHONE` previamente autorizado, **nunca** ao WhatsApp do interessado.
- Mensagem direta ao interessado só é usada se **não há rota interna** configurada; não inclui CPF ou dados do técnico. Esse caminho abre atendimento na Opa! Suite, mas o administrador precisa confirmar o roteamento do atendimento ao comercial.
- Webhook interno HTTPS e e-mail interno já recebem o JSON completo (CPF e técnico), portanto mantidos.
- Evita envio duplicado dentro de uma hora usando deduplicação em `leads.db`. Registros existentes não são reenviados automaticamente: verificar se já foram encaminhados antes de reencaminhar manualmente.

## Configuração no Ubuntu

A correção depende de configurar a rota **para o comercial** no `/home/suporte/app_ism/backend/.env`; não coloque tokens nem dados reais em mensagens. Para Opa! Suite interno:

```dotenv
WHATSAPP_PROVIDER=opa
OPA_BASE_URL=https://sua-opa.example
OPA_API_TOKEN=SEU_TOKEN_PRIVADO
OPA_CHANNEL_ID=SEU_CANAL
OPA_TEMPLATE_LEAD_ID=ID_DO_TEMPLATE_APROVADO_INTERESSADOS
OPA_COMMERCIAL_PHONE=71XXXXXXXXX
```

O template interno aprovado para `interessados` deve possuir **4 variáveis na ordem** nome, telefone, técnico (agora combinado com CPF), protocolo. Certifique-se de que seu texto aprovado aceite a informação adicional no terceiro campo. Em caso de recusa pelo Meta/Opa, crie e aprove um modelo de **5 variáveis** com campos separados antes de modificar a chamada.

A versão Android v0.12.4 e o endpoint de interessados permanecem compatíveis. Não precisa recompilar o APK para esse ajuste; atualizar somente o backend da branch v0.12.5. Não gere novo QR e nunca use `docker compose down -v`.

Comandos após verificar alterações locais:

```bash
cd /home/suporte/app_ism
git status --short
git fetch origin
git switch --track origin/fix/ism-backend-v0125-leads-cpf-tecnico-opa-20261118
cd backend
docker compose up -d --build api
curl -fsS https://app.ismtelecom.com.br/version
docker exec backend-api-1 python -m app.terminal_activation --status
```

Se a branch já existir, use `git switch <branch>` seguido de `git pull --ff-only`.

## Diagnóstico do que já está gravado (sem revelar CPF completo)

No Ubuntu:

```bash
docker exec -i backend-api-1 python - <<'PY'
import os, sqlite3
db=os.getenv('LEADS_DB','/data/leads.db')
with sqlite3.connect(db) as conn:
  for row in conn.execute(
    'SELECT protocolo,nome,substr(cpf,1,3)||\'***\'||substr(cpf,-2),'
    'tecnico,estado,canal FROM interessados ORDER BY criado_em DESC LIMIT 10'):
    print(row)
PY
```

A saída exibirá nome, CPF parcialmente mascarado, técnico, estado e canal; tenha cuidado com dados pessoais. A mensagem de aceite da Opa! Suite não comprova entrega: verificar o histórico na plataforma.
