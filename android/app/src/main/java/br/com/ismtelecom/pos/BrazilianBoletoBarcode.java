package br.com.ismtelecom.pos;

/** Converte linha digitável (47 ou 48) em código de barras de 44 dígitos. */
final class BrazilianBoletoBarcode {
    private BrazilianBoletoBarcode() {}

    static String convert(String source) {
        if (source == null || !source.matches("[0-9]{44}|[0-9]{47}|[0-9]{48}")) {
            throw new IllegalArgumentException("Linha digitável inválida");
        }
        if (source.length() == 44) return source;
        if (source.length() == 47) {
            // Boleto bancário: banco+moeda; DV geral; fator+valor; campo livre.
            return source.substring(0, 4) + source.charAt(32)
                    + source.substring(33, 47)
                    + source.substring(4, 9)
                    + source.substring(10, 20)
                    + source.substring(21, 31);
        }
        // Arrecadação: quatro grupos de 11 dados + 1 DV de campo.
        return source.substring(0, 11) + source.substring(12, 23)
                + source.substring(24, 35) + source.substring(36, 47);
    }
}
