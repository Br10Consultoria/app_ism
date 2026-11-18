# ISM v0.12.7 — captação por técnico e maquineta com link de indicação

## Fluxo confirmado pela ISM (18/11/2026)
1. O técnico preenche **nome, CPF, telefone, técnico e autorização** na GPOS780; o app envia também o UUID da instalação ativada pelo QR. **CPF e nome do técnico não vão no WhatsApp público.**
2. Backend confere CPF, autorização e que o UUID pertence ao registro de terminal já provisionado. Vínculo: protocolo aleatório `CAP-XXXXXXXXXXXX` + terminal `ISM-0001` + técnico + cliente/CPF/telefone no SQLite `/data/leads.db`. Não muda nem apaga dados antigos.
3. Opa envia **convite apenas para o celular do interessado**. **Não envia diretamente ao departamento comercial**, nem abre atendimento automaticamente.
4. Para incluir o **link individual** no convite, criar e aprovar no Opa! Suite/Meta um novo template de três variáveis: `{{1}}` primeiro nome, `{{2}}` marca ISM TELECOM, `{{3}}` URL individual `https://app.ismtelecom.com.br/i/CAP-XXXXXXXXXXXX`. Preencher `OPA_TEMPLATE_PROSPECT_LINK_ID`. **Não altere o ID do modelo existente de duas variáveis**. Sem o novo modelo, o convite antigo continua podendo ser enviado, mas SEM link (resposta `link_incluido=false`), e o app exibe opção de copiar manualmente o link.
5. Se o interessado quiser contratar, ele clica no link. Backend registra **apenas número de visitas**, sem IP/cookie, e faz redirect HTTP 302 para `https://wa.me/55DDD9XXXXXXXX?text=...`, em que o texto é “Olá! Quero conhecer os planos da ISM Telecom. Minha indicação: CAP-XXXXXXXXXXXX”. **O cliente precisa apertar Enviar no WhatsApp** para o comercial receber a indicação.
6. O operador comercial visualiza o protocolo na conversa e consulta a origem em `GET /api/v1/admin/captacoes/CAP-XXXXXXXXXXXX` passando **`X-ISM-ADMIN-KEY`**, uma chave administrativa distinta da senha compartilhada dos terminais. O retorno administrativo contém CPF/telefone e origem. **Nunca inserir ISM_ADMIN_KEY no APK, WhatsApp ou URL público.**
7. O clique no link NÃO representa contratação nem direito automático à comissão. Vincular resultado do contrato com os dados do IXC e regras de comissão em etapa administrativa separada.

## Configuração sem compartilhar segredos

Servidor `/home/suporte/app_ism/backend/.env`:

```dotenv
WHATSAPP_PROVIDER=opa
OPA_PROSPECT_WHATSAPP_ENABLED=true
OPA_BASE_URL=https://DOMINIO_REAL_DA_SUA_OPA
OPA_API_TOKEN=SEGREDO_ODA
OPA_CHANNEL_ID=ID_DO_CANAL
OPA_TEMPLATE_PROSPECT_ID=ID_MODELO_ANTIGO_DUAS_VARIAVEIS
# Preencher SOMENTE quando o modelo de 3 variáveis estiver aprovado
OPA_TEMPLATE_PROSPECT_LINK_ID=
COMMERCIAL_WHATSAPP_PHONE=71XXXXXXXXX
ISM_ADMIN_KEY=CHAVE_LONGA_ALEATORIA_32_OU_MAIS_CARACTERES
```

O **número de destino** e o template não foram fornecidos nesta conversa. Sem essas duas configurações, não há envio de link individual automático, mesmo que o backend e APK sejam atualizados. O nome e telefone da equipe não devem ser inventados.

Gere chave administrativa no Ubuntu: `openssl rand -hex 32` (salve **somente** em `.env`), e restrinja acesso ao arquivo. Recriar o contêiner com `docker compose up -d --build api`, **não usar `down -v` nem regenerar QR**.

**Testes de segurança:** a rota pública do link só redireciona, não exibe CPF, técnico, telefone ou quem captou. Use `GET /api/v1/admin/captacoes/CAP-... ` somente em ferramenta administrativa sob TLS, com `X-ISM-ADMIN-KEY`. Idealmente adicionar autenticação por operador, MFA e registro de consultas antes da liberação em produção.

## Para ativar no Opa! Suite

Criar novo modelo de mensagem aprovado com terceiro campo dinâmico de link. Sugestão (texto é exemplo; Meta pode exigir revisão do link e da categoria):
```text
Olá {{1}}! Somos da {{2}}. Foi um prazer atender você.
Se quiser saber mais sobre os nossos planos e falar com o comercial,
acesse seu link de indicação: {{3}}
```
Este é apenas o texto proposto; só utilize o ID aprovado no Opa e confirme como a API da Opa exige envio de variáveis/URLs dinâmicas.

## Atualizar o backend

```bash
cd /home/suporte/app_ism
git status --short
git fetch origin
git switch --track origin/feat/ism-v0127-captacao-rastreavel-release-20261118
cd backend
docker compose up -d --build api
curl -fsS https://app.ismtelecom.com.br/version
docker exec backend-api-1 python -m app.terminal_activation --status
```

Se a branch já existir localmente: `git switch feat/ism-v0127-captacao-rastreavel-release-20261118 && git pull --ff-only`. Esperado: versão `0.12.7` e QR ATIVO.

## APK GPOS780 988B

O CI desta branch só executa **`:app:assembleRelease`**, sem build Debug, e verifica `application-debuggable` ausente, `versionCode=27`. Para testes, o workflow de PR assina o Release com certificado Android **temporário autoassinado** com CN=ISM Telecom. É um certificado de homologação, **não** a assinatura do fabricante e **não** a chave de produção. Só assine no Robô Gertec e instale em um terminal piloto; a troca de certificados pode exigir desinstalação e perder o UUID/numeração local. Para atualizar sem perder dados em produção, configure os Secrets `ISM_RELEASE_*` com keystore permanente da empresa e os requisitos do Robô Gertec.

## Checklist funcional

- Captar um interessado autorizado com CPF válido + telefone com DDD + técnico + checkbox.
- Conferir número da maquineta no cadastro administrativo e protocolo CAP.
- Sem modelo novo, convite antigo funciona sem link e app avisa claramente; copiar link para testar manualmente.
- Com modelo novo aprovado e configurado, cliente recebe link no convite; abre WhatsApp comercial com texto CAP preenchido.
- Confirmar que a mensagem só chega após o cliente apertar Enviar.
- Consultar protocolo via API administrativa; conferir técnico, CPF e ISM-NNNN sem imprimir ou compartilhar CPF em logs.
- Confirmar consulta de faturas, PIX, impressão GEDI e envio de boleto, sem regressões.
