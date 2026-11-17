# ISM Telecom v0.12.0 — APK Release para Robô de Assinatura Gertec

## Motivo

O documento Gertec **Aplicação em Debug.pdf**, páginas 2–5, explica que o Robô de Assinatura **rejeita `android:debuggable=true`** e solicita novo APK em modo **release**. O workflow anterior executava `:app:assembleDebug`, portanto seu APK não deveria ser submetido ao Robô para produção.

O projeto agora executa **`:app:assembleRelease`**, com `debuggable false` e verificação automatizada do pacote, versão e ausência de `application-debuggable` no manifesto final com `aapt dump badging`.

### Dois resultados possíveis do Actions

- **CI pull_request (sem chave de assinatura):** artefato `ism-telecom-v0.12.0-gpos780-988b-release-NAO-ASSINADO`, contendo `app-release-unsigned.apk`. Ele **não** possui `android:debuggable=true`, mas também **não está assinado**. Só envie ao Robô se a Gertec confirmar que aceita APK *unsigned* para receber a assinatura final do fabricante. Não pode ser instalado diretamente no Android.
- **Actions > Run workflow** na branch com os quatro secrets cadastrados: artefato `ism-telecom-v0.12.0-gpos780-988b-release-assinado`, contendo `app-release.apk`. O workflow valida a assinatura com `apksigner verify --verbose --print-certs` e ausência da flag debug. **Esse é o preferível para tentar no Robô**, mas Gertec ainda poderá exigir a assinatura própria/certificado GPOS720/760/780/790 Varejo.

O nome do pacote (`applicationId`) permanece **`br.com.ismtelecom.pos`**, `versionCode=22`, `versionName=0.12.0`. O backend sempre aponta para `https://app.ismtelecom.com.br`, inclusive em execução de PR. GEDI 2.2.6, PPComp 1.39, HCL 1.0.10 e a logo ISM são integrados a partir do SDK 988B **privado**, preservado na branch de homologação.

## Criar sua chave Android Release no Windows — uma única vez

Esta é uma **chave de desenvolvedor ISM**, diferente do certificado de dispositivo que o Robô Gertec aplica na etapa posterior. **Não a envie por conversa, e-mail ou para o repositório.** Faça backup seguro da chave e das senhas; perdê-las pode impedir atualizações com a mesma identidade Android. Não use a chave debug gerada pelo Android Studio/GitHub.

No PowerShell do Windows, com `keytool.exe` do JDK ou do Android Studio instalado:

```powershell
# Se 'keytool' não estiver no PATH, use a versão incluída no Android Studio:
$keytool = "$env:ProgramFiles\Android\Android Studio\jbr\bin\keytool.exe"

$chave = "$env:USERPROFILE\Documents\ISM_Telecom_Release.p12"
if (Test-Path $chave) {
    throw "A chave já existe. NÃO recrie: faça backup e reutilize a atual."
}

& $keytool -genkeypair -v -storetype PKCS12 `
    -keystore $chave -alias ism-telecom `
    -keyalg RSA -keysize 3072 -validity 10000 `
    -dname "CN=ISM Telecom, O=ISM Telecom, C=BR"
```

O `keytool` pedirá senha de forma interativa; guarde-a num gerenciador seguro. Um keystore PKCS12 usa a mesma senha de arquivo e chave para melhor compatibilidade com ferramentas Java.

No GitHub (repositório **privado** `Br10Consultoria/app_ism`) abra:

**Settings → Secrets and variables → Actions → New repository secret**.

Cadastre **quatro secrets**:

| Secret | Valor |
|---|---|
| `ISM_RELEASE_KEYSTORE_B64` | Base64 integral da chave `.p12` (copiado pelo comando abaixo) |
| `ISM_RELEASE_STORE_PASSWORD` | Senha definida para `ISM_Telecom_Release.p12` |
| `ISM_RELEASE_KEY_ALIAS` | `ism-telecom` |
| `ISM_RELEASE_KEY_PASSWORD` | Mesma senha da chave PKCS12 |

Para colocar o conteúdo Base64 no clipboard no Windows (não exibe chave na tela):

```powershell
$chave = "$env:USERPROFILE\Documents\ISM_Telecom_Release.p12"
[Convert]::ToBase64String([IO.File]::ReadAllBytes($chave)) | Set-Clipboard
```

Cole o conteúdo diretamente no secret `ISM_RELEASE_KEYSTORE_B64`, salve e depois limpe o clipboard. Jamais cole esse texto em issue, pull request, mensagem ou arquivo do repositório. Depois defina os três outros secrets. Se o GitHub bloquear upload de secrets pelo tamanho, use GitHub CLI `gh secret set` ou peça orientação específica; não faça commit da chave em texto/base64 no repo.

### Gerar APK assinado

1. Acesse **Actions → Validar backend e gerar APK Release Gertec → Run workflow**.
2. Selecione a branch **`feat/ism-v0120-gertec-988b-native-20261116`**.
3. Execute. A etapa `Preparar chave privada Release` detectará os secrets e disponibilizará a chave temporária apenas nesse job.
4. Confira se **Android** e **Backend** ficaram verdes.
5. Baixe o artefato **`ism-telecom-v0.12.0-gpos780-988b-release-assinado`**. Extraia `app-release.apk` e anexe somente esse APK ao portal da Gertec.
6. Confirme no log do job `Validar modo Release, pacote e assinatura` que aparece **`apksigner verify`** sem erro e **`Release verificado: android:debuggable nao ativo`**.
7. No Robô, escolha a família GPOS720/760/780/790 **Varejo**, Homologação **Padrão**, SmartStore **Não** e **GPOS780**, conforme documento do fabricante; preencha grupo MDM correto antes de distribuir.
8. **Homologue em UMA maquineta.** Aplicar assinatura de fabricante pode mudar certificado Android. Atualizações sobre APK antigo de outra chave podem exigir desinstalação e perder ativação local. Não distribua para o grupo inteiro antes do teste.

A senha/chave fica no GitHub Actions Secrets e o arquivo é criado em `RUNNER_TEMP` somente em execução manual autorizada. **Eventos `pull_request` nunca recebem secrets**, impedindo que código de PR alterado acesse a chave.

## Arquivos e verificações

- Workflow: `.github/workflows/build.yml`.
- Build Android: `android/app/build.gradle`.
- SDK 988B: `scripts/install_gertec_sdk.py` e `android/app/sdk/ISM_GPOS780_988B_SDK_PRIVADO.zip` na branch privada.
- Proteção local: `.gitignore` ignora `*.p12`, `*.jks`, `*.keystore`, `*.pfx` e outros arquivos sensíveis.

**Atenção:** o backend 0.12.0 é atualizado separadamente por `docker compose up -d --build api` preservando volumes. A mudança de assinatura afeta somente a compilação do APK; não precisa reiniciar o backend.
