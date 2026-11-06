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
