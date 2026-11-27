package br.com.ismtelecom.pos;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

/**
 * Comprovante TEF devolvido pelo m-SiTef. Não acrescenta cabeçalhos, altera
 * valores, elimina espaços, quebra linhas ou corta o texto do autorizador.
 * Falha em vez de truncar uma linha que não couber nos 384 pontos do GEDI.
 */
final class TefReceipt {
    private static final int WIDTH = 384;
    private static final int MARGIN = 8;
    private TefReceipt() {}

    static Bitmap render(String text) {
        if (text == null || text.isEmpty() || text.length() > 12000) {
            throw new IllegalArgumentException("Via TEF ausente ou muito grande");
        }
        String[] rows = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        if (rows.length > 210) {
            throw new IllegalArgumentException("Comprovante TEF com linhas demais");
        }
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        ink.setTypeface(Typeface.MONOSPACE);
        ink.setColor(Color.BLACK);
        ink.setTextSize(15f);
        // Escala somente o tamanho da fonte; nunca abrevia, reordena nem quebra o texto.
        for (String row : rows) {
            if (row.indexOf('\t') >= 0 || row.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("Caracteres de controle não suportados");
            }
        }
        float largest = 0;
        for (String row : rows) largest = Math.max(largest, ink.measureText(row));
        while (largest > WIDTH - 2f * MARGIN && ink.getTextSize() > 9f) {
            ink.setTextSize(ink.getTextSize() - .5f);
            largest = 0;
            for (String row : rows) largest = Math.max(largest, ink.measureText(row));
        }
        if (largest > WIDTH - 2f * MARGIN) {
            throw new IllegalArgumentException("Texto longo demais para imprimir sem cortes");
        }
        int leading = (int) Math.ceil(ink.getFontSpacing()) + 3;
        int height = MARGIN * 2 + rows.length * leading;
        if (height > 7000) throw new IllegalArgumentException("Cupom TEF muito longo");
        Bitmap image = Bitmap.createBitmap(WIDTH, Math.max(height, 35), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.drawColor(Color.WHITE);
        float baseline = MARGIN - ink.ascent();
        for (String row : rows) {
            canvas.drawText(row, MARGIN, baseline, ink);
            baseline += leading;
        }
        return image;
    }
}
