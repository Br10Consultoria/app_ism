# ISM Telecom v0.12.3 — testes Gertec GPOS780 firmware 988B

## Melhorias desta versão

- **Enviar boleto** abre opções **e-mail do IXC** ou **outro e-mail**.
- **Outro e-mail** solicita endereço e confirmação de autorização do titular, envia código de 6 dígitos ao próprio destinatário (10 min), exige o código, obtém PDF diretamente do IXC e envia por SMTP da ISM. O endereço original no IXC **não é alterado**. O endpoint IXC `get_boleto/mail` não aceita escolha de destino: serve exclusivamente para envio cadastrado.
- Código de verificação guardado somente como HMAC com segredo do backend, máximo 5 tentativas, 90 s entre solicitações, até 4 códigos/hora por fatura/endereço. Evita encaminhamento a endereço digitado erradamente.
- **Imprimir código** cria recibo horizontal: conteúdo 960x384px, gira 90º e imprime como bitmap 384x960px na bobina da GPOS780, com ITF bancário completo 44 dígitos (sem redimensionamento). Informações: número da fatura IXC, emissão, vencimento, valor, beneficiário **quando fornecido pelo IXC**; caso ausente, informa essa indisponibilidade. A tela do aplicativo não abre PDF.
- Na tela **Escolha como pagar** ficam somente **PIX**, **Cartão de crédito (em breve)** e **Cartão de débito (em breve)**. Crédito/débito seguem indisponíveis e não executam nenhuma transação financeira.
- Rodapé **somente na home**, inspirado no material de referência do cliente: atendimento ISM **(71) 3334-0606** e instrução de uso da tela sensível ao toque.
- QR Code compartilhado existente, numeração ISM-0001, e-mail nativo IXC, PIX e sessão de 300 segundos preservados.

## Configuração adicional necessária no backend: SMTP

O servidor v0.12.1 já envia e-mail para o endereço **cadastrado no IXC** por `GET /get_boleto` com `tipo_boleto=mail`, usando o SMTP do próprio ERP. Esse fluxo **não muda**.

Para **outro endereço**, a API do IXC usada não suporta destinatário arbitrário. A v0.12.3 usa o **SMTP próprio da ISM**. O fluxo alternativo ficará indisponível (HTTP 503, explicando ausência do SMTP) até configurar no arquivo `/home/suporte/app_ism/backend/.env`:

```dotenv
BOLETO_EMAIL_ENABLED=true
BOLETO_SMTP_HOST=smtp.gmail.com
BOLETO_SMTP_PORT=465
BOLETO_SMTP_USER=conta-do-workspace@seudominio.com.br
BOLETO_SMTP_PASSWORD=senha-de-app-ou-credencial-autorizada
BOLETO_SMTP_FROM=conta-do-workspace@seudominio.com.br
BOLETO_EMAIL_DB=/data/boleto_email.db
```

Os valores são **exemplos**, não credenciais existentes. Usar somente SMTP/relay corporativo autorizado pela conta Workspace, sem compartilhar senha na conversa ou gravá-la no GitHub. Verificar no `backend/docker-compose.yml` se `env_file: [.env]` realmente propaga essas variáveis ao contêiner; não presumir isso sem checagem.

Se o IXC devolver apenas URL do boleto (em vez do PDF Base64), o backend não busca esse URL por segurança; retorna erro explicativo. A rotina padrão do IXC continua funcionando.

## Release / Robô de Assinatura

O workflow cria:
- **Debug** para teste local somente, **nunca enviar ao robô**;
- **Release sem Debug** (sem assinatura Android quando secrets não configurados);
- **Release assinado pelo desenvolvedor** apenas quando os 4 secrets `ISM_RELEASE_*` forem configurados pela ISM e o workflow for iniciado manualmente na branch.

Assinatura Android **não é autenticada pelo texto do nome da empresa**. Um certificado com `CN=ISM Telecom` criado sem uma chave privada gerenciada pela ISM não comprova titularidade e não substitui a assinatura Gertec. O certificado para atualizações futuras deve ser permanente e guardado sob controle da ISM. Veja `docs/GERTEC_ASSINATURA_RELEASE.md`.

Baixe **app-release.apk** ou **app-release-unsigned.apk** conforme o estado explicitado no artefato, e confirme com a Gertec se ela aceita **unsigned**. Não renomeie um APK Debug como Release; não reinstale por cima de um app com assinatura diferente sem avaliar perda dos dados de ativação.

## Teste de homologação

1. Faça backup do banco `/data/terminal_activation.db` antes do deploy. Não regenerar QR e não executar `docker compose down -v`.
2. Verifique `curl -fsS https://app.ismtelecom.com.br/version` = `0.12.3`.
3. Confirme `docker exec backend-api-1 python -m app.terminal_activation --status` = ATIVO.
4. Na GPOS780, confirme `Terminal ISM: ISM-NNNN` e fluxo CPF → faturas.
5. Teste e-mail cadastrado (IXC) e e-mail novo (código recebido e depois PDF), conferindo recebimento; não use um boleto de cliente sem autorização.
6. Imprima uma cobrança real autorizada e compare todos os **44 dígitos e o valor** com o boleto original. Teste leitura por aplicativo bancário. Não aceite pagamento de boleto ilegível.
7. Verifique rodapé e botões PIX/crédito/débito; cartões não devem cobrar.
8. Submeta Release ao Robô somente depois da verificação do pacote e do certificado; restrinja ao grupo MDM de teste.

## Limites importantes

- O fluxo alternativo exige acesso ao endereço informado; não envia sem código.
- A API do IXC confirma aceitação de envio nativo, não entrega da mensagem.
- A saída horizontal e o scanner devem ser homologados **na impressora física GPOS780**; compilar não comprova legibilidade do código de barras.
