# SDK privado Gertec GPOS780 — firmware 988B

Para compilar o ISM v0.12.0 com **impressão real na bobina** e **número de série físico**, faça upload do arquivo **`ISM_GPOS780_988B_SDK_PRIVADO.zip`** nesta pasta, na branch `feat/ism-v0120-gertec-988b-native-20261116`.

**Importante:** este repositório é privado. Não publique o ZIP ou o SDK em repositórios públicos. O código e a logo foram fornecidos pela equipe da ISM Telecom para homologação.

O arquivo ZIP preparado para esta versão contém:

- `vendor/libgedi-2.2.6-b801c08-gpos780Neo-payment-release.aar`
- `vendor/libppcomp-001.39-250807-gpos780-release.aar`
- `vendor/libhcl-1.0.10-gpos780-release.aar`
- `vendor/libgandi-2.1.19-668c185a-gpos780Neo-payment-release.aar`
- `assets/ism_logo_gpos780.png` (logo oficial transparente da ISM)

O workflow `.github/workflows/build.yml` chama `python3 scripts/install_gertec_sdk.py`, verifica os SHA-256 de cada AAR antes de compilar e copia só **GEDI + PPComp + HCL** para `android/app/libs`.

**GANDI não é necessário à impressão e leitura de serial** segundo a inspeção de referências das classes GEDI 2.2.6; o seu manifesto solicita diversas permissões privilegiadas que não são justificáveis para o app de cobranças, por isso fica no ZIP para análise, mas não é integrado ao APK.

Permissões GEDI `SET_TIME` e `READ_EXTERNAL_STORAGE` foram retiradas do manifesto final porque o ISM não as utiliza para consultar o serial ou imprimir.

## Compilação e validação

1. Envie o ZIP para **esta pasta na branch correta** pelo GitHub (Add file → Upload files → Commit changes).
2. A execução do GitHub Actions do PR deverá gerar o artefato `ism-telecom-v0.12.0-gpos780-sdk988b-native-apk`.
3. Instale somente em maquineta de homologação. APK de Actions tem assinatura debug.
4. Compare o número de série na tela inicial com a etiqueta da GPOS780.
5. Teste `Imprimir código` com uma cobrança autorizada; verifique bobina e legibilidade do ITF 44 dígitos.

Não instale `ATUALIZADOR_FULL_GPOS780_V988B_USERSEC.apk` para compilar o aplicativo ISM: ele é um pacote do firmware do dispositivo, não uma dependência Android de desenvolvimento.

**Não executar `docker compose down -v`** no servidor; isso afetaria dados persistidos e QR compartilhado.
