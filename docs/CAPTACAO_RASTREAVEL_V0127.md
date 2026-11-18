# ISM Telecom v0.12.7 — captação rastreável, sem mensagem interna automática

## Fluxo confirmado pela ISM

1. Técnico cadastra interessado no terminal e obtém consentimento, incluindo nome, CPF, telefone, identificação do técnico e terminal `ISM-0001` atribuído na ativação QR.
2. Backend salva dados completos em `/data/leads.db`, com protocolo único `CAP-XXXXXXXXXXXX`, terminal vinculado por UUID cadastrado pelo servidor (não por valor livre do APK). CPF **nunca** entra no link nem no WhatsApp do interessado.
3. Opa! Suite envia **somente ao WhatsApp do interessado** o convite com link individual `https://app.ismtelecom.com.br/i/CAP-...`. O cliente pode ignorá-lo.
4. **Se o cliente quiser**, clica no link, que abre `wa.me/<numero_comercial_autorizado>?text=...CAP-...`. O cliente ainda precisa pressionar Enviar no WhatsApp. O backend registra o clique mas clique **não é prova de contato, venda ou comissão**.
5. Comercial procura protocolo em consulta administrativa com `ISM_ADMIN_KEY` distinta da `TERMINAL_KEY` e recebe nome, CPF, telefone, técnico, terminal, data e número de acessos ao link. Somente pessoal autorizado consulta dados pessoais.
6. Comissão só poderá ser confirmada posteriormente por venda efetiva no IXC e regras de comissionamento aprovadas. **Nenhuma comissão é gerada automaticamente** nesta versão.

## Dependências de configuração (sem revelar segredos)

No servidor `/home/suporte/app_ism/backend/.env`:

```dotenv
WHATSAPP_PROVIDER=opa
OPA_BASE_URL=https://seu-opa-suite.exemplo.com.br
OPA_API_TOKEN=TOKEN_GUARDADO_SOMENTE_NO_SERVIDOR
OPA_CHANNEL_ID=CANAL_DA_ISM
OPA_PROSPECT_WHATSAPP_ENABLED=true
OPA_TEMPLATE_PROSPECT_ID=MODELO_ANTIGO_DE_DUAS_VARIAVEIS
OPA_TEMPLATE_PROSPECT_LINK_ID=NOVO_MODELO_APROVADO_COM_TRES_VARIAVEIS
COMMERCIAL_WHATSAPP_PHONE=71XXXXXXXXX
ISM_ADMIN_KEY=CHAVE_ADMIN_RANDOMICA_COM_PELO_MENOS_32_CARACTERES
```

**O modelo novo precisa ser aprovado no Opa/Meta** com **3 variáveis**: `{{1}}` primeiro nome; `{{2}}` marca ISM TELECOM; `{{3}}` URL individual. O template antigo permanece como fallback, mas **não envia o link**; o aplicativo sinaliza `link_incluido=false`, sem fingir que o convite rastreável saiu. O número comercial deve ser o número oficial informado pela ISM; não inventar número. A mensagem ao interessado não inclui CPF nem identificação do técnico.

Exemplo de mensagem no template novo:

> Olá, {{1}}! Somos da {{2}} e ficamos felizes em lhe atender. Se quiser conhecer nossos planos, abra este link: {{3}}.

Se o template tiver botão com URL dinâmica em vez de placeholder no corpo, confirmar o formato exigido pela API do Opa! antes de habilitar `OPA_TEMPLATE_PROSPECT_LINK_ID`; a implementação atual usa **3 variáveis de texto**.

**Nunca** colocar a `ISM_ADMIN_KEY` ou o token Opa em APK, WhatsApp ou repositório. Não imprimir CPFs em logs de testes.

## Consulta administrativa (servidor/autorizada)

```bash
curl -fsS -H 'X-ISM-ADMIN-KEY: <CHAVE_ADMIN_SOMENTE_NA_MAQUINA>' \
  https://app.ismtelecom.com.br/api/v1/admin/captacoes/CAP-XXXXXXXXXXXX
```

Em produção, use ferramenta que não registre a chave em histórico de shell nem nos argumentos de processos.

## Atualização servidor (necessária)

```bash
cd /home/suporte/app_ism
git status --short
git fetch origin
git switch --track origin/feat/ism-v0127-captacao-rastreavel-release-20261118
cd backend
docker compose up -d --build api
curl -fsS http://127.0.0.1:8765/version
curl -fsS https://app.ismtelecom.com.br/version
docker exec backend-api-1 python -m app.terminal_activation --status
```

Se a branch já existir localmente: `git switch feat/ism-v0127-captacao-rastreavel-release-20261118` e `git pull --ff-only`. **Não executar** `docker compose down -v`, que apagaria os dados de ativação. Faça backup do volume /data antes da atualização.

O acesso HTTPS público depende do proxy reverso existente; o servidor pode responder localmente mesmo que o Nginx esteja retornando 502.

## APK para Gertec

GitHub Actions gera **somente APK Release**, sem `android:debuggable`. Em eventos de PR, aplica certificado de homologação **autoassinado e temporário** (`CN=ISM Telecom`) para solicitar assinatura ao Robô Gertec e testar em uma maquineta. Cada CI gera certificado diferente. Para manutenção/atualização definitiva, usar chave permanente da ISM por GitHub Actions Secrets e homologação Gertec; assinatura temporária não assegura atualização de uma instalação anterior sem desinstalação.

## Testes na maquineta

- Verificar QR existente, `Terminal ISM: ISM-NNNN` e número estável.
- Realizar captação autorizada; obter protocolo `CAP-...`.
- Confirmar que a mensagem vai **apenas ao cliente** e inclui URL individual se novo template estiver habilitado.
- Acessar URL, conferir abertura do WhatsApp do comercial com protocolo; o usuário precisa enviar a mensagem.
- Confirmar no endpoint admin que CPF, técnico e terminal estão vinculados ao protocolo.
- Conferir que não existe envio automático ao número do comercial nem abertura obrigatória de atendimento.
- Não tratar clique como venda nem pagar comissão sem confirmação posterior da ISM.
