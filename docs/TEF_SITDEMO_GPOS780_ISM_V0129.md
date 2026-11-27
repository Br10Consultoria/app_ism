# ISM Telecom v0.12.9 — TEF m-SiTef + Getnet / SitDemo GPOS780

## Objetivo e limites

Esta versão **não processa transações financeiras reais**: utiliza o **m-SiTef 3.306 para GPOS780** e **SiTef SitDemo** que foi disponibilizado pela Gertec. As chamadas são feitas pelo app Java Android via Intent oficial `br.com.softwareexpress.sitef.msitef.ACTIVITY_CLISITEF`. Na maquineta, o m-SiTef deve estar instalado separadamente, conforme instruções da Gertec/MDM. O ISM não contém bibliotecas de captura de PIN/cartão, nem instala o aplicativo da Software Express automaticamente.

O TEF só é habilitado quando o backend confirma um **ambiente SitDemo configurado** e o Android encontra a Intent do m-SiTef. O resto do ISM permanece igual: QR compartilhado, identificação ISM-0001, PIX, impressão de boleto horizontal, e-mail e captação.

Fonte: ZIP `SDK+-+m-SiTef+-+GPOS780.zip` recebido da Gertec; arquivos `m-SiTef _ Guia de Integração.pdf`, `m-SiTef App Exemplo.zip`, `Roteiro de PreHomologacao_m-SiTef_GPOS780_v3.1.2.docx`. A configuração SitDemo veio de Rafael Bueno (Gertec), exclusivamente homologação.

## Respostas oficiais da Gertec

- `empresaSitef=00000000` para SitDemo.
- `enderecoSitef` é o endereço do **Windows que executa o SitDemo**. Pode ser informado depois da compilação do APK.
- `cnpj_automacao=11716083000136` (empresa certificada ISM Telecom, conforme definição da ISM).
- `CNPJ_CPF=11716083000136` (estabelecimento ISM Telecom).
- `comExterna=0` no SitDemo local, **não 4**. TLS/registro de token só serão usados quando a Gertec habilitar produção. Não aplicar empresa `00000000` ou `comExterna=0` em produção.
- Identificação do terminal: manter em branco enquanto não for definido pelo roteiro de configuração do SitDemo; o m-SiTef pode usar a identificação do hardware.
- O m-SiTef de demonstração fornecido no ZIP é compatível com GPOS780 988B, segundo a Gertec.

## Instalação no Windows com PRTG

1. Fazer backup/ponto de recuperação no Windows, pois hospeda o PRTG existente.
2. Extrair `SitDemo.zip` do pacote da Gertec para pasta própria. Seguir `Manual SitDemo_8.0.pdf` e `Manual de Instalação e Utilização do SiTef_8.0.pdf` incluídos no pacote. Não alterar serviços ou portas do PRTG.
3. Configurar o SitDemo para escutar na interface **VPN/LAN privada**, não na interface WAN pública. Anotar IP acessível da maquineta e a porta real apresentada no manual/configuração do SitDemo; **não inventar nem abrir portas em toda a internet**.
4. Estabelecer VPN site-to-site ou WireGuard entre a rede da maquineta e o Windows, ou aplicar ACL na borda e Windows Firewall com origem estritamente autorizada. Firewall público sozinho não fornece confidencialidade: VPN é preferível.
5. Validar conectividade ao SitDemo a partir da GPOS780; acesso do backend ao Windows não substitui o acesso **direto do terminal ao SitDemo**.
6. Instalar o `msitef_3.306-GPOS780_001.31-230802-117.108.r2_sit6s-demo.apk` pelo processo autorizado da Gertec, antes de tentar o cartão. O APK ISM não substitui o m-SiTef.

## Configuração do backend (somente após instalar o SitDemo)

Em `/home/suporte/app_ism/backend/.env`:

```dotenv
CARD_TEF_HOMOLOGATION_ENABLED=false
CARD_TEF_MODE=sitdemo
CARD_TEF_DB=/data/tef_attempts.db
MSITEF_EMPRESA=00000000
MSITEF_COM_EXTERNA=0
MSITEF_ENDERECO=IP_PRIVADO_OU_VPN_DO_WINDOWS_SITDEMO
MSITEF_CNPJ_ESTABELECIMENTO=11716083000136
MSITEF_CNPJ_AUTOMACAO=11716083000136
MSITEF_TERMINAL=
```

**Mantenha o switch `CARD_TEF_HOMOLOGATION_ENABLED=false` até confirmar IP, rede, app m-SiTef e teste autorizado.** O app em Release pode ser instalado antes disso, mas os botões TEF continuarão bloqueados. Após configurar tudo, definir `...=true` e reiniciar **somente a API**.

Não inserir dados SiTef no código Android, e jamais colocar token de produção, PIN, senha, PAN ou chaves em logs ou GitHub.

### Atualização sem apagar dados do QR

```bash
cd /home/suporte/app_ism
git fetch origin
git switch --track origin/feat/ism-v0129-sitef-sitdemo-homologacao-20261127
# Se a branch já existir localmente: git switch <branch> && git pull --ff-only
cd backend
# Antes de editar, faça backup seguro de .env e do volume persistente ism_leads.
# Edite SOMENTE as linhas TEF em .env (nunca sobrescreva todo o arquivo).
docker compose up -d --build api
curl -fsS http://127.0.0.1:8765/version
curl -fsS https://app.ismtelecom.com.br/version
```

**NUNCA use `docker compose down -v` ou apague `/data`**, pois contém QR, ativação de terminais e cadastros de interessados. O arquivo de tentativas TEF será persistido em `/data/tef_attempts.db`.

## Fluxo de teste

1. Instalar APK ISM v0.12.9 **Release** pelo robô Gertec, se exigido. A assinatura Android de homologação gerada pelo CI é temporária (não é assinatura Gertec). Um APK com certificado diferente pode exigir desinstalação e reativação; preserve seus dados/QR de instalação.
2. Abrir `Boletos e pagamentos`, consultar um **cliente e fatura de TESTE autorizados** no IXC e escolher `Pagar boleto`.
3. Se m-SiTef instalado e backend habilitado, botões Crédito/Débito ficam ativos. O PIX continua independente.
4. Confirmar teste: backend reconsulta fatura IXC, bloqueia paga/cliente errado, e obtém **valor aberto diretamente do IXC**, não do APK.
5. O ISM chama o m-SiTef com `modalidade=2` débito ou `3` crédito à vista, restrições `TransacoesHabilitadas=16` / `26`, `numeroCupom` único e `comExterna=0`.
6. Após a resposta, ISM registra situação/NSU/autorização em auditoria e mostra duas opções **Imprimir via do cliente** e **Imprimir via do estabelecimento**, usando GEDI na GPOS780. Não modifica texto ou adiciona campos ao comprovante.
7. **Nenhuma baixa, alteração de saldo ou integração financeira escrita no IXC**. Resposta positiva do SitDemo não significa pagamento real.
8. Cada tentativa fica registrada e bloqueia outra tentativa para a mesma fatura. Após erro, interrupção, timeout ou impressão, NÃO repita na mesma fatura sem conciliação/auditoria. O desbloqueio controlado de testes e rotinas gerenciais (cancelamento/reimpressão/trace modalidades 110/121) são requisitos de homologação subsequentes.

## Pendências para homologação oficial

- Instalar e configurar Windows SitDemo em rede privada alcançável pela GPOS780, sem interferir no PRTG.
- Testar fisicamente m-SiTef, GEDI e comprovantes originais na GPOS780; executar funções gerenciais (cancelamento, reimpressão, teste de conexão, trace). **Apenas pagamento à vista e impressão das vias retornadas estão implementados neste marco.**
- Submeter roteiro oficial v3.1.2, comprovantes numerados e evidências para a Gertec/Fiserv.
- Definir certificado de automação/assinatura permanente, parâmetros Getnet e token TLS **apenas na fase de produção**.
- Conceber autorização de baixa e conciliação IXC após confirmação externa; não habilitar no SitDemo.
