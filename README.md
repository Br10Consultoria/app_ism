# ISM Telecom — App Android Gertec GPOS780

Aplicativo de consulta de cobranças do ERP **IXC Provedor**, destinado a terminais Gertec GPOS780 (Android 11) e equipe de campo.

## Fluxos e interface no POS (v0.2)
- **Tela inicial:** dois botões grandes, "Quero contratar" e "Boletos e pagamentos", seguindo as referências ISM da Gertec.
- **Faturas:** consulta CPF/CNPJ, cards vermelho (vencidas) e verde (em aberto), filtro e ações Imprimir / Enviar / Pagar.
- **Sessão:** contador visual de 180 segundos igual à sessão real do backend, limpando dados ao expirar.
- **Imprimir / Enviar:** usa impressão e compartilhamento padrão do Android sobre PDF seguro; **a impressora térmica interna da Gertec ainda precisa de SDK específico** e instalação de serviço compatível.

- **Pix:** digitar CPF, escolher fatura aberta e visualizar Pix copia e cola / QR Code quando disponível no IXC.
- **Código de barras:** digitar CPF, escolher fatura e exibir a linha digitável fornecida pelo IXC.
- **Boleto:** digitar CPF, escolher fatura e visualizar o PDF ou abrir seu link quando fornecido pelo IXC.
- **Indicar novo cliente:** técnico informa CPF, telefone, nome opcional e consentimento; solicitação é encaminhada ao comercial por webhook HTTPS ou e-mail.

## Estrutura
- `android/`: projeto Android nativo (Java, minSdk 26, targetSdk 35), tela em retrato para POS 720×1280, compatível com AndroidX FileProvider.
- `backend/`: API intermediária FastAPI; somente o backend mantém a credencial do IXC.
- `docs/INSTALACAO.md`: implantação, permissões, teste, validação das carteiras de cobrança e geração de APK.

## Segurança
**Não insira as credenciais do IXC no APK.** A API exige chave de terminal em cabeçalho e HTTPS; proteja o aparelho com modo de terminal/MDM, restrinja acesso ao backend por rede/IP, troque chaves periodicamente e adicione MFA/OTP antes de disponibilizar a busca pública por CPF sem supervisão. CPF por si só não autentica o titular; o MVP destina-se a uso supervisionado pelo técnico.

A consulta não cria cobrança nem altera recebimento. Pix/linha/link aparecem **apenas se forem retornados e verificados na instalação IXC**. O aplicativo não inventa Pix nem substitui o beneficiário.

A impressora térmica interna da GPOS780 exige SDK e credenciamento Gertec, não incluídos no projeto. A versão 0.2 permite visualizar PDF, enviar via compartilhamento Android e imprimir via serviço Android compatível; a impressão na térmica interna permanece pendente.

## Começando
1. Copie `backend/.env.example` para `backend/.env`; informe URL e token IXC, chave do terminal e canal comercial.
2. Suba o backend seguindo `docs/INSTALACAO.md` e valide com um **CPF de testes autorizado**.
3. Configure `ismBackendUrl` e `ismTerminalKey` em `android/gradle.properties` localmente (nunca comite tokens) e gere o APK.
4. Instale somente em equipamento autorizado; configure o servidor com HTTPS válido.

**Status:** Android compilado e testes unitários do backend aprovados no GitHub Actions. Consulte **Actions → Validar backend e gerar APK de testes → Artifacts → ism-telecom-debug-apk**. O APK gerado pelo CI usa configuração vazia de servidor/chave e serve para validar instalação/interface; para conexão real, gere uma build própria com `android/gradle.properties` configurado. Conexão IXC, disponibilidade dos dados de cobrança, envio comercial e hardware Gertec ainda exigem homologação na instalação.
