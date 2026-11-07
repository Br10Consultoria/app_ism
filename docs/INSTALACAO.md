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
- Leads só serão confirmados após aceite do webhook/e-mail. Resposta 503 significa que não foi encaminhado; o técnico deve tentar novamente.
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
