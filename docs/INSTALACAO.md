# Instalação — ISM Telecom / GPOS780

## Requisitos e limites
- Gertec GPOS780 Android 11, tela 5,5" 1280x720, memória 2GB e conectividade Wi-Fi/4G; dados conforme ficha GPOS780 fornecida.
- API IXC com IP do backend autorizado, URL HTTPS e token com permissões **mínimas** para cliente, financeiro e operações de leitura Pix/boleto.
- Servidor Ubuntu 24.04, Docker + Compose e **proxy HTTPS** com certificado público válido. Não abrir a porta interna 8765 publicamente.
- Notificação comercial: webhook HTTPS de CRM/automação **ou** SMTP SSL. Não colocar credenciais comerciais nem IXC no APK.

## Backend
```sh
git clone https://github.com/Br10Consultoria/app_ism.git
cd app_ism/backend
cp .env.example .env
# edite .env (IXC_URL, IXC_USER_ID, IXC_TOKEN,
# TERMINAL_KEY, SESSION_SECRET e canal comercial)
docker compose up -d --build
curl -fsS http://127.0.0.1:8765/health
```
A API escuta apenas na interface loopback do servidor. Use Nginx/Caddy com TLS, por exemplo `https://api-ism.seudominio.com.br` encaminhando para `http://127.0.0.1:8765`. Restrinja firewall, aplique rate limit por IP, permita somente terminais gerenciados e limite requisições por CPF na borda. Nunca disponibilize o serviço com chave padrão.

## Validação da API
Use CPF de testes autorizado, nunca CPF real em capturas de tela/logs. Exemplo:
```sh
curl -sS -X POST 'https://api-ism.seudominio.com.br/api/v1/faturas' \
 -H 'Content-Type: application/json' -H 'X-Terminal-Key: SUA_CHAVE' \
 -d '{"cpf":"CPF_DE_TESTES_VALIDO"}'
```
O retorno contém `session` efêmera (180 s) e `faturas`; aceita CPF ou CNPJ válido no campo `cpf` para manter compatibilidade de API. Selecionar `id` de uma fatura para:
```sh
curl -sS -X POST 'https://api-ism.seudominio.com.br/api/v1/faturas/ID/pix' \
 -H 'Content-Type: application/json' -H 'X-Terminal-Key: SUA_CHAVE' \
 -d '{"session":"TOKEN_DA_CONSULTA"}'
```
Troque `pix` por `barra` ou `boleto` conforme a fatura. Uma fatura é reconsultada no IXC antes de entregar códigos e deve pertencer ao cliente da sessão.

**IXC ISM homologado (06/11/2026):** `get_pix` (GET) retorna EMV em `pix.qrCode.qrcode`; `get_boleto` usa POST com `boletos`, `tipo_boleto=arquivo` e `base64=S`, retornando Base64 puro de PDF, apesar do MIME `text/html`. Não registrar credenciais, PIX ou dados pessoais. A geração do boleto pode ter efeitos de registro bancário: testar apenas cobranças autorizadas.

## Android / APK
1. Android Studio recente, SDK Android 35 / JDK 17 e dispositivo liberado para APK próprio. Gertec pode exigir habilitação pelo adquirente/administrador de aplicações.
2. Na pasta `android/`, crie `gradle.properties` localmente a partir de `gradle.properties.example`.
3. Defina `ismBackendUrl=https://api-ism.seudominio.com.br` e `ismTerminalKey=...`, ambos com valores de teste/produção próprios (chaves de terminal em APK são extraíveis; a segurança deve incluir gestão do dispositivo, lista IP/rede e rotação).
4. Abra a pasta no Android Studio, sincronize Gradle e execute `assembleDebug`. APK: `app/build/outputs/apk/debug/app-debug.apk`. Na compilação manual do GitHub Actions, configure o segredo do repositório `ISM_TERMINAL_KEY` e execute `Run workflow` na branch principal, obtendo `ism-telecom-homologacao-apk`. A compilação automática de PR permanece sem acesso real.
5. Instale na GPOS780 via mecanismo autorizado pela Gertec/adquirente, valide em Wi-Fi e 4G, tela, foco numérico, expiração da sessão, faturas com e sem Pix, retorno de boleto e encaminhamento comercial.
6. Gerar `assembleRelease` com assinatura própria, versão e política MDM/quiosque quando aprovado.

## Impressão
Esta versão mostra PIX em QR, linha digitável, PDF por PdfRenderer, envio de PDF via Intent ACTION_SEND + FileProvider e impressão padrão via Android Print Framework. **A impressora térmica interna Gertec ainda não está integrada**: solicitar SDK oficial de impressão GPOS780, integração AIDL/serviço e permissões antes de alegar impressão direta na maquininha.

## Interface de atendimento
- Tela inicial com duas opções (Quero contratar / Boletos e pagamentos), inspirada nas capturas fornecidas.
- Cards de fatura com cor de acordo com data de vencimento, filtro Todas / Em aberto / Vencidas.
- Os três botões por fatura chamam a API validada. Pagar oferece PIX, linha digitável e PDF; Enviar abre compartilhamento Android; Imprimir abre serviço Android instalado.
- Saudações não mostram o nome do titular: CPF isolado não autentica identidade. Exibir nome completo depende de autenticação mais forte.
- O contador indica 180 segundos (TTL da API); não é um recurso de pagamento.
- A opção ID Cliente no formulário comercial é opcional, pois o interessado pode ainda não ter cadastro.

## Proteção de dados
- CPF isolado não prova identidade. Uso apenas **supervisionado** com técnico autenticado e dispositivo inventariado. Para autoatendimento público, exija OTP por telefone/e-mail cadastrado no IXC e reforço de rate limit por CPF.
- Não armazenar CPF, telefone ou faturas em preferências persistentes do Android; o app limpa a tela ao retornar.
- Leads são registrados primeiro no SQLite persistente do servidor, mesmo sem webhook/SMTP/Opa comercial configurado. Protocolo e situação informam se estão pendentes, encaminhados ou em revisão. O operador não deve afirmar que uma mensagem foi entregue apenas porque o registro foi salvo.
- Proteger logs do reverse proxy, TLS, backups e acesso a variáveis do serviço.
- Não habilitar transação financeira por cartão sem homologação PCI/SDK/adquirente.

## Homologação Android v0.3 — ícone, navegação e chave local
- Launcher usa ícone vetorial ISM Telecom, em vez do robô padrão Android.
- Cabeçalho apresenta botões com texto **Voltar** e **Sair**. Sair confirma a ação, encerra a sessão e limpa o PDF temporário.
- URL padrão de consultas: `https://app.ismtelecom.com.br`. Não há necessidade de compilar a chave no APK.
- No Android, abra **Configurar terminal** e cole somente `TERMINAL_KEY` do backend. A chave fica cifrada pelo Android Keystore; nunca informe `IXC_TOKEN` no aplicativo.
- O botão **Apagar chave salva** desvincula o terminal localmente. Após salvar, testar o CPF autorizado para confirmar autenticação. Guardar o terminal em ambiente supervisionado.
- O fluxo GitHub Actions manual com `ISM_TERMINAL_KEY` permanece opcional, mas embutir o segredo no APK é menos seguro do que o pareamento local.
- Limitação: a chave compartilhada de terminal ainda não identifica o cliente; para autoatendimento público, implementar autenticação forte, rate limit adequado e identificação individual de dispositivos.

## PIX — confirmação e compartilhamento v0.4 (homologação)

Fluxo: consultar CPF/CNPJ autorizado -> selecionar cobrança em aberto -> PIX -> apresentar QR Code -> cliente paga em seu banco -> **Verificar pagamento PIX**. O botão chama `POST /api/v1/faturas/{id}/confirmar-pix` usando a mesma sessão (TTL de 180 segundos) e chave de terminal.

A consulta é **somente leitura** e valida novamente se a cobrança pertence ao cliente autenticado na sessão. O app só apresenta **PIX RECEBIDO** se o IXC retornar: `status=R`, `recebido_via_pix=S`, `aguardando_confirmacao_pagamento != S`, `estornado != S`, saldo aberto não positivo e `valor_recebido` positivo. Qualquer informação faltante ou inconclusiva não gera confirmação. A baixa pelo banco pode ser assíncrona.

Quando confirmado, o Android gera um PDF **Confirmação de recebimento registrada no IXC** com ID da cobrança, valor, data (se houver), timestamp de verificação e TxID (se disponível). O arquivo contém a ressalva de que **não é o comprovante bancário original**. O operador toca em **Enviar confirmação pelo WhatsApp**; WhatsApp instalado abre para escolher destinatário. Caso indisponível, abre o menu de compartilhamento do Android. Não há envio automático nem acesso a contatos sem autorização.

Não envie a confirmação quando pagamento estiver pendente, parcial, estornado, cancelado, ou identificado como recebido por outro meio. Não utilize o endpoint para alterar status no IXC. A função de cobrança via cartão foi deliberadamente excluída.

### Testes
- Testar primeiro com dados simulados (testes automatizados). Depois, usar cobrança **autorizada**, que já esteja baixada como PIX no IXC, para validar classificação dos campos da carteira local. Não pagar faturas reais apenas para homologar.
- Verificar que fatura de outro cliente retorna 404, sessão inválida retorna 401, em aberto não gera PDF e falha de comunicação retorna erro sem sinalizar pagamento.
- Em aparelho real, conferir PDF e compartilhamento para um contato explicitamente escolhido, sem incluir CPF no documento ou no texto.
- O comprovante bancário original deve ser obtido pelo pagador em seu banco; esta funcionalidade não o substitui.

## ISM v0.5: consultar PIX já recebidos
- Após entrar em Boletos e pagamentos e consultar um CPF/CNPJ autorizado, o app apresenta **Consultar PIX já recebidos**, inclusive quando não houver nenhuma cobrança aberta.
- A sessão temporária identifica o cliente durante 180 segundos. A API `POST /api/v1/faturas/pix-recebidos` pesquisa **os 100 títulos mais recentes** desse cliente no IXC (`fn_areceber.id` decrescente) e devolve, no máximo, **10** títulos marcados como quitados via PIX. A busca é limitada: títulos muito antigos podem não aparecer.
- O usuário escolhe um título da lista e toca em **Verificar e enviar confirmação**: o app repete a consulta à fatura no IXC em tempo real e *somente se o recebimento PIX continuar confirmado* habilita o PDF para compartilhar por WhatsApp.
- Se o PIX buscado não estiver nos 100 títulos mais recentes, informar diretamente o **número da fatura IXC** na tela e tocar em **Verificar pelo número da fatura**; o backend consulta somente aquela fatura, confere a titularidade e só confirma PIX realmente quitado. Exemplo de homologação: fatura 218848 (PIX) versus fatura 295590 (outro meio) — usar CPF/CNPJ autorizado correspondente.
- Se a baixa demorar além de 180 segundos, faça nova consulta por CPF/CNPJ. Não é necessário pagar outra vez nem recuperar uma sessão vencida.
- A consulta e os comprovantes são apropriados **apenas em atendimento supervisionado por técnico**. CPF/CNPJ por si só não autentica o titular; implementar validação adicional antes de disponibilizar em autoatendimento público.
- Não há recebimento por Getnet nesta etapa. O QR Code continua sendo emitido pelo IXC; o WhatsApp abre no Android para envio manual pelo técnico.

## ISM v0.6 — PIX, homologação Android e sessão de 60 segundos

- O servidor retorna `GET /version` com versão `0.6.0` e `sessao_segundos: 60`, sem expor segredos. Verificar com `curl -fsS https://app.ismtelecom.com.br/version`; caso responda 404, o backend ainda não foi atualizado.
- A sessão do backend e do Android expira em **60 segundos**, contados desde a consulta CPF/CNPJ. Ao expirar, o aplicativo limpa os dados e volta à tela inicial. Para confirmar pagamentos que baixem depois disso, faça uma nova consulta.
- Na tela do QR Code PIX o app consulta a baixa no IXC **a cada 8 segundos**, desde que a sessão esteja ativa. Quando confirmada, mostra a tela "PIX RECEBIDO" e permite gerar o PDF para compartilhamento no WhatsApp. Também existe o botão "Verificar pagamento agora". A ausência de confirmação **não significa falha no pagamento bancário**.
- A tela "Consultar PIX já recebidos" agora abre **antes** de buscar a lista. Mesmo que a consulta dos 100 títulos mais recentes falhe, o técnico pode informar o número de uma fatura PIX já quitada e verificar diretamente, sempre validando que pertence ao CPF/CNPJ da sessão.
- O retorno de erro 404 para a rota de listagem agora orienta a atualizar o backend. Antes de instalar o novo APK, confirmar `GET /version` e redeploy do Docker.
- Fluxo de homologação: utilizar uma fatura PIX previamente recebida no IXC, como a 218848, em uma sessão de cliente autorizado; conferir status, valor e compartilhamento. **Não realizar pagamentos adicionais sem necessidade.**
- Continua sendo um sistema de atendimento **supervisionado**, pois CPF/CNPJ sem validação adicional não garante a identidade do titular. O PDF é confirmação do registro IXC, não comprovante do banco pagador.

## ISM v0.7 — cliente identificado e 60 segundos a partir da tela PIX

- A API `POST /api/v1/faturas` retorna `cliente.nome` (campo `razao` do IXC, com fallback para `nome`, `nome_razao`, `fantasia`) e CPF/CNPJ formatado, somente depois de localizar o cliente pela busca exata do documento. Não expõe endereço, senha nem credenciais.
- O aplicativo mostra o **Nome do cliente** e **CPF/CNPJ** na lista de faturas, histórico PIX e tela de pagamento confirmado. Esses dados não são persistidos no terminal nem inseridos no PDF da confirmação.
- Sessão dura **60 segundos** a partir de cada operação que a renova. `POST /api/v1/sessao/renovar` exige chave de terminal + sessão previamente válida; o Android só chama quando o técnico **abre Consultar PIX já recebidos** ou após uma confirmação PIX bem-sucedida. Não é uma renovação automática infinita; ao expirar, consultar o CPF/CNPJ novamente.
- Ao entrar na tela **PIX já recebidos**, o cronômetro é reiniciado para 60 segundos. Ao confirmar uma fatura, reinicia novamente para que haja 60 segundos para compartilhar o documento no WhatsApp.
- Ao expirar, a tela de dados sensíveis é fechada sem modal bloqueante: o usuário retorna à home e aparece uma mensagem breve (Toast).
- `GET /version` deve responder `{"app":"ISM Telecom","versao":"0.7.0","sessao_segundos":60}` após atualização do Docker.
- Teste operacional: consultar CPF autorizado, observar nome/CPF e tempo; tocar em Consultar PIX já recebidos e conferir reinício em 60 segundos; escolher PIX previamente confirmado no IXC, enviar o arquivo por compartilhamento; testar que título não-PIX não recebe confirmação.
- Em aparelho Android onde o WhatsApp não aparece no menu de compartilhamento, instalar/autorizar WhatsApp ou WhatsApp Business (se permitido na maquininha) antes de validar envio. O compartilhamento depende de o aplicativo estar disponível.
- **Segurança:** exibição de CPF exige atendimento supervisionado. Para operação não supervisionada, adicionar OTP/validação do titular e mascaramento. Use somente CPF de homologação autorizado e não envie prints com dados pessoais.


## ISM v0.8 — WhatsApp Cloud API via backend (sem WhatsApp no Android)

**Fluxos separados:**
- **Boas-vindas de novos assinantes:** o cliente precisa estar cadastrado como novo no IXC, ativo e com `data_cadastro` recente (últimos 30 dias por padrão). Após consulta do CPF/CNPJ pelo técnico, a opção só aparece se o IXC tiver WhatsApp/celular válido e o backend Meta estiver configurado. O operador precisa confirmar que o cliente consentiu. Nunca confundir este caso com **Quero contratar**, que é apenas captação de interessado.
- **Confirmação de PIX:** na tela com `PIX RECEBIDO`, o operador toca **Enviar confirmação PIX via API WhatsApp** e confirma autorização. O backend valida novamente a titularidade e a baixa PIX real no IXC (`status=R`, `recebido_via_pix=S`, sem estorno, saldo zero e valor positivo); somente então aciona o template Meta.
- **PDF via Android** permanece como alternativa, mas não é enviado automaticamente à Meta. A mensagem de pagamento por Meta é *texto* de confirmação do registro no IXC, não comprovante bancário.

**Configuração no Ubuntu (arquivo `backend/.env`, jamais no APK/GitHub):**

```dotenv
META_WHATSAPP_ACCESS_TOKEN=COLE_AQUI_TOKEN_OFICIAL_DE_SISTEMA
META_WHATSAPP_PHONE_NUMBER_ID=COLE_AQUI_PHONE_NUMBER_ID_META
META_GRAPH_VERSION=v26.0
META_TEMPLATE_LANGUAGE=pt_BR
META_TEMPLATE_WELCOME=ism_boas_vindas_cliente
META_TEMPLATE_PIX=ism_pix_recebido
META_NEW_CLIENT_MAX_DAYS=30
META_OUTBOX_DB=/data/whatsapp_outbox.db
```

Os nomes são **exemplos**: precisam ser substituídos pelos nomes dos templates que realmente estejam **aprovados/ativos** no WhatsApp Manager da ISM, com código de idioma `pt_BR` e quantidades e ordem de variáveis exatas. Um token de usuário de sistema precisa ter permissões WhatsApp Cloud API sobre o `phone_number_id` correto.

**Texto sugerido para template `ism_boas_vindas_cliente` (2 parâmetros):**

> Olá, {{1}}! Seja muito bem-vindo(a) à ISM TELECOM! Estamos muito felizes em ter você como nosso cliente. Agradecemos a confiança em nossa conexão! ID {{2}}. Aqui estão alguns links úteis para facilitar seu dia a dia: Central do Assinante: https://ixc.ismtelecom.com.br/central_assinante_web/login (boletos e faturas). Suporte Técnico: 71 3334-0606.

**Texto sugerido para template `ism_pix_recebido` (4 parâmetros):**

> Olá, {{1}}! A ISM TELECOM confirmou no IXC um recebimento via PIX de R$ {{2}}, referente à fatura {{3}}, com data de pagamento {{4}}. Esta é uma confirmação do registro de recebimento no IXC e não substitui o comprovante bancário original. Suporte: 71 3334-0606.

As categorias Utility/Marketing são definidas e validadas pela Meta; evite promoções em mensagens transacionais. As mensagens fora da janela de atendimento exigem modelo aprovado. A conta de origem **não é o chip da maquininha**, mas o número oficial vinculado à WhatsApp Business Platform da ISM.

**Cuidados e limites:**
- API não aceita número de celular informado pelo Android. Destinatário é `whatsapp` ou `telefone_celular` consultado novamente no cadastro do IXC, validado para celular BR e mostrado mascarado na confirmação. Um celular cadastrado não significa consentimento: o operador deve ter autorização do cliente e confirmar antes de enviar.
- O envio não é disparado automaticamente ao pagar nem ao consultar CPF. O operador solicita e confirma.
- Envio de boas-vindas exige novo cadastro confirmado pelo IXC (data nos últimos `META_NEW_CLIENT_MAX_DAYS`; máximo 90 dias) e só pode ser solicitado uma vez por cliente; confirmação PIX uma vez por fatura. Deduplicação persistente por hash HMAC no SQLite `/data` (volume Docker).
- Em caso de timeout/resultado incerto da Meta, o evento fica em revisão para evitar segundo envio involuntário. Verifique o WhatsApp Manager antes de repetir. Um HTTP 200 com `wamid` informa **aceitação pela Meta**, não entrega; status de entrega requer webhooks Meta (etapa posterior).
- Sem token e templates aprovados o backend continua atendendo faturas/PIX, mas os botões Meta ficam indisponíveis. `GET /version` passa a retornar `0.8.0` após atualizar o Docker.
- Mensagens WhatsApp Meta podem ter custos. Exibir somente dados do cliente autenticado na sessão supervisionada; para autoatendimento público, implementar verificação independente do titular (OTP ou equivalente).

## ISM v0.9 — instalador de testes, ativação única e compatibilidade Getnet/Gertec

- **APK Android padrão**: compatível em requisitos de SO com a Gertec GPOS780 (Android 11, minSdk do aplicativo é 26/Android 8). A instalação física nas maquininhas **Getnet de produção** depende da certificação, assinatura e distribuição autorizadas pela adquirente. Debug APK serve para aparelhos Android de teste e terminais de desenvolvimento; Getnet produtiva pode bloquear sideload/USB.
- A primeira abertura do aplicativo sem chave exibe automaticamente **Ativar terminal ISM**. Técnico autorizado insere `TERMINAL_KEY` e o Android faz `POST /api/v1/terminal/validar` autenticado no backend antes de salvar. Uma chave errada é rejeitada e não fica armazenada.
- Após ativar, a chave é mantida no Android Keystore, **não é embutida no APK**, e a tela de configuração/desvinculação **não aparece** novamente. O app não expõe API de alteração da chave. Para reativar em laboratório, administrador pode apagar dados do app nas configurações do Android e ativar de novo; isso não é proteção contra quem administra o sistema operacional.
- A validação de chave precisa do backend **v0.9** instalado antes do novo APK: `curl -fsS https://app.ismtelecom.com.br/version` retorna `versao=0.9.0`. `/api/v1/terminal/validar` exige a chave; não expõe clientes nem dados.
- Artefato GitHub Actions: `ism-telecom-v0.9-ativacao-unica-apk` (debug). Nem mesmo `workflow_dispatch` aceita chave por variável de compilação; `TERMINAL_KEY` vem da digitação protegida na primeira abertura.
- As opções de WhatsApp Cloud API ficam **inativas até configurar token, número remetente e modelos de mensagem aprovados** no servidor. A GPOS780 não precisa ter WhatsApp instalado. O chip fornece acesso à internet; o remetente é o WhatsApp Business da empresa.
- O documento GETNET/Software Express que apresenta DTE/X25 e módulo de TEF descreve conexão **de pagamentos eletrônicos por adquirente**, e não o mecanismo necessário para a consulta PIX no IXC nem WhatsApp Cloud API.
- Para equipe de homologação: distribuir somente a operadores autorizados, sem publicar `TERMINAL_KEY` em grupos, APKs ou screenshots. Esta primeira fase possui chave compartilhada entre terminais e CPF sem verificação adicional; **não colocar em autoatendimento público**. Para escala de produção, planejar cadastro por aparelho, revogação individual, autenticação do assinante e assinatura de distribuição.

## Integração Opa! Suite via API (ISM, v0.9)

A ISM já utiliza Opa! Suite como WhatsApp Business integrado ao IXC. **Use o mesmo canal existente** em vez de registrar diretamente o número na Meta. A API externa Opa usa autorização `Bearer`, configurada em um usuário dedicado com grupo de permissões API. O canal e os modelos de mensagem devem estar ativos/aprovados e com autorização de envio. Consulte:
- https://central-opasuite.ixcsoft.com.br/documentacao/api/api-opa%21-suite
- https://api.opasuite.com.br/
- https://wiki-erp.ixcsoft.com.br/documentacao/menu-configuracoes/integracoes/integracoes-omnichannel/integracao-omnichannel---opa-suite.html

O endpoint documentado de envio proativo de modelo é `POST /api/v1/template/send`. O backend monta o corpo sem aceitar canal, template, destinatário ou texto vindos do Android:

```json
{
  "contato": {"canalCliente": "+5571999999999"},
  "template": {
    "_id": "ID_DO_TEMPLATE_APROVADO",
    "variaveis": ["Nome", "33"]
  },
  "canal": "ID_DO_CANAL_WHATSAPP"
}
```

**Configure no arquivo privado `backend/.env` do Ubuntu:**

```dotenv
WHATSAPP_PROVIDER=opa
OPA_BASE_URL=https://DOMINIO-DA-INSTALACAO-OPA
OPA_API_TOKEN=TOKEN_DO_USUARIO_DE_API
OPA_CHANNEL_ID=ID_DO_CANAL_WHATSAPP
OPA_TEMPLATE_WELCOME_ID=ID_DO_MODELO_BOAS_VINDAS
OPA_TEMPLATE_PIX_ID=ID_DO_MODELO_PIX_RECEBIDO
WHATSAPP_OUTBOX_DB=/data/whatsapp_outbox.db
META_NEW_CLIENT_MAX_DAYS=30
```

O Opa! Suite usa **IDs de modelo e canal** (não apenas o nome textual). Localize os IDs no painel ou pelos endpoints `GET /api/v1/canal-comunicacao/` e `GET /api/v1/canal-comunicacao/{id}/template`. Não enviar token nem dados de assinantes ao ChatGPT.

- Modelo de boas-vindas: valores na ordem `[primeiro_nome, id_cliente]`.
- Modelo PIX: valores `[primeiro_nome, valor_recebido, numero_fatura, data_pagamento]`.
- Formatos e quantidades de variáveis precisam corresponder exatamente aos modelos cadastrados no Opa.
- Destinatário sempre reconsultado do cadastro IXC no backend; o Android apenas autoriza solicitação mediante confirmação de consentimento.
- O Opa! Suite retorna `status=success` com `data.messageSentId`. Isso indica **envio solicitado/aceito**, não que a Meta entregou ao telefone; a entrega deve ser conferida no histórico/status do Opa.
- A versão v0.9 conserva suporte à integração direta Meta para outros ambientes, mas com `WHATSAPP_PROVIDER=opa` **o fluxo usa somente o Opa! Suite**.
- Mensagem fora da janela WhatsApp de 24h exige modelo aprovado; o Opa! Suite gerencia o canal oficial.
- No backend, a deduplicação é gravada no volume `/data` em SQLite. Não publica número completo nem token nas respostas.
- O endpoint de texto livre `POST /api/v1/atendimento/mensagem/send` requer `customerServiceId` de atendimento existente; não foi escolhido para os disparos de boas-vindas.
- Não é necessário aplicativo WhatsApp na maquininha: o chip fornece conectividade para falar via HTTPS com `app.ismtelecom.com.br`, que conversa com o Opa.

**Pré-requisito para distribuir:** atualizar backend com `docker compose up -d --build` e validar `GET /version = 0.9.0`. A instalação no GPOS780 da Getnet pode exigir autorização/assinatura da adquirente, independentemente da compatibilidade Android. APK debug serve para Android normal e terminais de desenvolvimento/homologação.

## ISM v0.10 — Corrigido "Comercial indisponível" no Quero contratar

O erro na v0.9 ocorria porque `POST /api/v1/interessados` exigia `COMMERCIAL_WEBHOOK_URL` ou SMTP, mesmo quando o Opa! Suite estava configurado apenas para os modelos dos assinantes.

**Agora são etapas separadas:** cadastro persistente do interessado e aviso ao comercial. Após CPF, telefone, nome, identificação do técnico e consentimento, o backend salva o registro em **SQLite** no volume `/data/leads.db` (variável `LEADS_DB`), deduplica o mesmo CPF+telefone por 1 hora e devolve um protocolo de 12 caracteres.

- Sem canal comercial configurado: `200`, `enviado:false`, `situacao:pendente`. O Android mostra **Contato registrado — encaminhamento pendente**, sem afirmar envio.
- Com canal válido: o sistema tenta Opa (modelo comercial) ou webhook HTTPS/SMTP e retorna `enviado:true` somente após aceite do canal, sem confundir aceitação com entrega ao time.
- Com erro/timeout de entrega: o registro permanece salvo com `situacao:revisar`. Para não duplicar mensagens, não há reenvio automático desse caso; é preciso checar o histórico do canal.
- O telefone e CPF ficam restritos ao banco no servidor. Não são retornados ao app na confirmação nem gravados em log pela API. Proteger volume, backups e acesso administrativo; aplicar política de retenção/eliminações conforme LGPD.

Para avisar a equipe pelo mesmo canal Opa! Suite, configure **somente depois de criar um modelo próprio aprovado**:

```dotenv
WHATSAPP_PROVIDER=opa
OPA_BASE_URL=https://DOMINIO_OPA_DA_ISM
OPA_API_TOKEN=TOKEN_JA_CONFIGURADO
OPA_CHANNEL_ID=679cc77501d3afa6ad0a1178
OPA_TEMPLATE_LEAD_ID=ID_DE_MODELO_NOVO_INTERESSADO
OPA_COMMERCIAL_PHONE=DDD9XXXXXXXX
LEADS_DB=/data/leads.db
```

Template sugerido (`OPA_TEMPLATE_LEAD_ID`, 4 variáveis nesta ordem):

> Novo interessado ISM Telecom: {{1}}. Telefone de contato: {{2}}. Técnico responsável: {{3}}. Protocolo: {{4}}. O interessado autorizou o contato comercial.

Para facilitar o atendimento, **não incluir CPF no template de WhatsApp da equipe**; o registro completo fica apenas no banco interno. Nunca reutilizar os modelos `data_cake`, `boas_vindas` ou `financeiro_boleto` para aviso interno do comercial. O destinatário deve ser um número de celular da equipe com autorização para receber essas mensagens.

Comandos administrativos no próprio servidor (não no app):

```bash
# Listar protocolos e telefones parcialmente mascarados, sem CPF
cd /home/suporte/app_ism/backend
docker compose exec -T api python -m app.commercial_leads pendentes

# Após configurar o canal comercial, tentar apenas os registros pendentes
docker compose exec -T api python -m app.commercial_leads encaminhar
```

Não execute `encaminhar` antes de ter um modelo próprio aprovado e destinatário autorizado. Verifique casos `revisar` diretamente no Opa para evitar mensagens duplicadas. A confirmação de cadastro comercial não depende do modelo PIX recebido nem altera a integração de cobranças.

**Para atualizar:** `git switch main && git pull --ff-only origin main`, depois `cd backend && docker compose up -d --build`. O endpoint `GET /version` deve mostrar `0.10.0`. Instale o APK v0.10 se desejar que o Android mostre a situação correta e o protocolo.

## ISM v0.11 — Quero contratar: iniciar conversa com interessado pelo Opa

A ISM utiliza o canal WhatsApp Cloud ativo `679cc77501d3afa6ad0a1178`. Foi identificado o modelo já existente `iniciar_atendimento2`, ID `6aa3fcaad95fdf7490e33108`, com duas variáveis, conforme a API da instalação consultada na homologação:

> Olá {{nome_cliente}}! Somos da {{nome_empresa}} e ficamos felizes em lhe atender. Podemos iniciar o atendimento agora?

**Este é o texto do modelo existente.** Não substitui o texto por "Aguarde" nem altera template aprovado unilateralmente. O status de aprovação real precisa ser confirmado no painel Opa. Se a ISM quiser outra redação, criar ou alterar modelo para análise/aprovação.

Para habilitar a opção **no backend**, após homologar permissões e destinatário autorizado:

```dotenv
WHATSAPP_PROVIDER=opa
OPA_CHANNEL_ID=679cc77501d3afa6ad0a1178
OPA_TEMPLATE_PROSPECT_ID=6aa3fcaad95fdf7490e33108
OPA_PROSPECT_WHATSAPP_ENABLED=true
```

Manter o `OPA_BASE_URL`, `OPA_API_TOKEN`, as configurações do IXC e as chaves de terminal que já funcionam. Sem habilitar `OPA_PROSPECT_WHATSAPP_ENABLED=true`, o cadastro comercial continua sendo salvo como pendente, como na v0.10. Este controle evita envio real involuntário na atualização.

Fluxo:
1. Interessado informa nome, CPF, **celular WhatsApp (DDD+9 dígitos)** e técnico na maquininha; confirma autorização explícita para a ISM entrar em contato por WhatsApp ou telefone.
2. Backend salva o cadastro `/data/leads.db` com protocolo e impede duplicações no intervalo de uma hora.
3. Se integração configurada, API Opa recebe `POST /api/v1/template/send`, com número do interessado obtido do formulário consentido, o modelo acima, as variáveis `[primeiro_nome, "ISM TELECOM"]` e `openCustomerService: true`.
4. Se a API retornar aceitação, aplicativo mostra **Interesse registrado** e informa que a solicitação de mensagem foi aceita. **Entrega e roteamento ao departamento comercial ainda precisam ser confirmados no Opa**. Sem canal configurado, aplicativo mostra **Contato registrado — pendente**, preservando protocolo.
5. Se houver atendimento já ativo, o Opa pode bloquear o modelo por padrão: confira o histórico antes de repetir, para evitar duplicação.

Esta opção não usa WhatsApp instalado na maquininha e não requer credenciais Meta nela. O número do remetente é o canal oficial Opa, não o chip da Gertec. Se desejar que o atendente comercial assuma essas conversas, configurar fluxo/roteamento e departamentos no Opa Suite; `openCustomerService` **não garante a escolha do departamento**.

A API do Opa documenta `openCustomerService: true` no envio de template: https://api.opasuite.com.br/. Configure o modelo para a finalidade correta, consulte status e permissões antes do primeiro teste real. Não use `data_cake` (boas-vindas a assinantes) para interessados.

Atualização: `git switch main && git pull --ff-only origin main`; `cd backend && docker compose up -d --build`; conferir `GET /version = 0.11.0`; instalar APK v0.11.
