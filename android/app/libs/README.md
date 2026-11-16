# Gertec GPOS780 — SDK da impressora e número de série

O Android da ISM utiliza **GEDI** para tentar imprimir diretamente, sem abrir o PDF:

- `GEDI.init(Context)`, `GEDI.getInstance(Context)`, `IGEDI.getPRNTR()`, `IPRNTR.Init()`, `Status()`, `DrawPictureExt(bitmap)`, `Output()`.
- Identificação física: `IGEDI.getINFO()` e `IINFO.ControlNumberGet(GEDI_INFO_e_ControlNumber.SN)`.

O arquivo **Gertec - Printing With GEDI.rar** fornecido pelo usuário contém `gedi_payment-1.14.15.1.aar`, um exemplo antigo. Essa versão **não é homologada para a GPOS780**; não foi incorporada a este build.

O portal de integrações da GPOS780 cita bibliotecas específicas da família GPOS780 (por exemplo `gedi-payment-gpos780:1.16.19`, `PPComp-gpos780:1.31`, `libhcl-gpos780:1.0.6` em uma matriz de referência). As versões exatas precisam acompanhar o firmware/adquirente de cada maquineta.

## Como habilitar impressão física

1. Baixe no portal da **Gertec** o pacote SDK **GPOS780 compatível com a versão de firmware do aparelho**.
2. Copie para esta pasta `android/app/libs/` os AAR/JAR do pacote oficial necessários a GEDI/PPComp/HCL. Conserve apenas versões coerentes entre si. **Não misture** o AAR da GPOS700 do exemplo com o kit GPOS780.
3. O `android/app/build.gradle` já inclui `implementation fileTree(dir: 'libs', include: ['*.aar', '*.jar'])`; rode o build pelo GitHub Actions ou Android Studio e confira que o APK contém GEDI e as libs nativas compatíveis.
4. Teste uma fatura autorizada tocando **Imprimir código**. Observe papel, bordas, leitura do ITF 44 dígitos e se o valor/vencimento coincidem com o IXC.

Sem esses arquivos o APK ainda compila, mas mostra uma **prévia** e mensagem de biblioteca indisponível. A prévia não é uma impressão física.

## Número de série

Na tela inicial a ISM lê `ControlNumberGet(SN)` do GEDI e apresenta **Nº de série Gertec**. O botão **Copiar identificação do terminal** facilita cadastrar a maquineta e o funcionário responsável.

Sem SDK ou permissão da Gertec o aplicativo mostra **Nº de série Gertec: indisponível** e, se existir, um **ID Android (não é serial)** como identificação provisória. O Android 11 não libera o número de série de fábrica por `Build.SERIAL` para aplicativos comuns.

O número de série não é enviado ao backend e não altera o QR compartilhado ou a chave de ativação. Registrar responsável, data de entrega e devolução no controle patrimonial fica para a rotina de operações.

## Homologação pendente

- Confirmar versão GEDI/PPComp/HCL da GPOS780.
- Verificar assinatura do APK homologada no MDM.
- Validar impressão física na bobina da GPOS780.
- Validar serial físico informado pelo SDK, comparando com a etiqueta do equipamento.
