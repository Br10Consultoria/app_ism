# ISM v0.12.1 — numeração fixa do terminal no QR de ativação

## Regra operacional

O padrão é **ISM-0001**, **ISM-0002**, **ISM-0003**, ... .

A mesma imagem de QR compartilhado atual (gerada em 2026-11-13) continua válida. Ela **não contém um número fixo** (pois é igual para todos os aparelhos). Ao escanear, o aplicativo gera e salva um identificador aleatório único da instalação e o envia junto do código de ativação ao backend. O backend valida o QR e reserva o próximo número ISM na tabela `terminal_registry` do mesmo SQLite `/data/terminal_activation.db`, no volume Docker persistente `ism_leads`. O backend devolve o número; o aplicativo grava o código no armazenamento privado e nunca oferece tela para editar.

Uma nova leitura do QR pela mesma instalação retorna o mesmo número. Uma instalação diferente recebe o próximo número disponível. O cadastro não depende da leitura do número de série físico da Gertec.

**IMPORTANTE:** Android não permite garantir identidade física imutável apenas com identificador gerado pelo app. A numeração é imutável **pela interface normal e em atualizações que preservam os dados locais**. A desinstalação, limpeza de dados, clonagem de armazenamento ou redefinição de fábrica pode apagar o UUID local e resultar em novo número. Para controle patrimonial mais forte, integre o cadastro ao MDM ou serial físico, sob autorização adequada.

## Migração da versão 0.12.0

Terminais já ativados na v0.12.0 não precisam apagar a chave, desinstalar ou reescanear o QR. A home da v0.12.1 chama uma única rota autenticada `POST /api/v1/terminal/identificar` **em fila independente da consulta IXC**, registra a instalação e passa a exibir `Terminal ISM: ISM-0001`. Se o backend ainda estiver antigo ou sem rede, aparece `Terminal ISM: cadastro pendente`; a próxima abertura da home tenta novamente.

**Removido completamente:** chamada ao SDK GEDI para serial ao abrir a home. Essa chamada podia ficar bloqueada e segurava a única fila de operações de rede, incluindo `Boletos e pagamentos → Avançar`.

A logo da home foi reduzida de 176dp para 112dp; os cabeçalhos das outras telas permanecem 72dp. A impressão térmica via GEDI nas telas específicas permanece disponível na GPOS780. O QR compartilhado atual não é regenerado.

## Backend

- `POST /api/v1/terminal/ativar-qr` aceita o campo novo `installation_uuid` (UUIDv4); retorna `terminal_id` e `terminal_key`. APKs antigos sem `installation_uuid` continuam com o contrato anterior.
- `POST /api/v1/terminal/identificar` usa `X-Terminal-Key` e `installation_uuid`; retorna o mesmo `terminal_id`.
- SQL transacional `BEGIN IMMEDIATE`, unique em installation_uuid e sequencial autoincremental. Preserva `terminal_shared_activation` e os códigos de uso único.
- Não adicionar ID no QR compartilhado, porque um QR idêntico não pode identificar mais de uma maquineta.
- Números são atribuídos na primeira ativação/migração bem-sucedida, em ordem de chegada. Não há reserva antecipada para equipagem de patrimônio.

### Atualização segura do servidor

```bash
cd /home/suporte/app_ism
git status --short
git fetch origin
git switch --track origin/fix/ism-v0121-qr-terminal-id-cpf-home-20261117
cd backend
docker compose up -d --build api
curl -fsS https://app.ismtelecom.com.br/version
docker exec backend-api-1 python -m app.terminal_activation --status
```

Se branch já existe: `git switch fix/ism-v0121-qr-terminal-id-cpf-home-20261117 && git pull --ff-only`. Faça backup do SQLite/volume antes da atualização em produção. **Não use `docker compose down -v`**. O esperado é `0.12.1` e QR compartilhado `ATIVO`.

## Android

O CI gera dois artefatos: **Debug para teste local** (não enviar para Robô de Assinatura Gertec) e **Release não Debug** (assinatura definitiva depende dos secrets `ISM_RELEASE_*` e do processo Gertec). A versão Android mantém `applicationId=br.com.ismtelecom.pos`, `versionCode=23`, `versionName=0.12.1`. Ativação manual legado passa pelo cadastro autenticado na próxima home.

Se uma versão anterior tiver **assinatura diferente**, instalar por cima pode falhar com `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Não desinstale automaticamente se quiser preservar a chave e UUID do terminal; o caminho recomendado de distribuição é a assinatura Gertec/MDM consistente.

## Verificações

1. Confirmar que o backend responde v0.12.1 e QR permanece ATIVO.
2. Em uma GPOS780 sem ativação, escanear o QR existente e conferir número ISM-0001 (ou próximo número disponível), sem opção de edição.
3. Reabrir app e conferir o mesmo número.
4. Em outra GPOS780, confirmar número distinto, ISM-0002 (ou próximo).
5. Na primeira GPOS780, entrar em Boletos e pagamentos e usar um CPF autorizado. O botão Avançar não deve depender de inicialização do SDK GEDI.
6. Testar sem rede: a home não trava e mantém último número salvo; as consultas de CPF exibem erro de conexão.
7. Confirmar tamanho da logo na home e cabeçalhos das outras telas.
