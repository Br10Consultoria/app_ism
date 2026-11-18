package br.com.ismtelecom.pos;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.util.Collections;

/**
 * Boleto térmico HORIZONTAL para a GPOS780 (papel de 384 pontos).
 *
 * Renderiza primeiro 960x384 na orientação de leitura horizontal e depois gira
 * 90º, gerando a imagem física de 384x960. Assim os 44 dígitos ITF ocupam o
 * comprimento do papel, com barras de largura inteira e margens de silêncio.
 * Não redimensionar após ZXing: barras borradas são rejeitadas por leitores.
 */
final class ThermalReceipt {
    private static final int PAPER_WIDTH = 384;
    private static final int LANDSCAPE_WIDTH = 960;
    private static final int LANDSCAPE_HEIGHT = 384;

    private ThermalReceipt() {}

    static Bitmap render(String id, String emission, String due,
                         String amount, String barcode, String beneficiary)
            throws Exception {
        if (barcode == null || !barcode.matches("[0-9]{44}")) {
            throw new IllegalArgumentException("Código bancário de 44 dígitos inválido");
        }

        Bitmap landscape = Bitmap.createBitmap(
                LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(landscape);
        canvas.drawColor(Color.WHITE);
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        ink.setColor(Color.BLACK);
        ink.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        ink.setTextSize(24);

        canvas.drawText("ISM TELECOM  |  DADOS PARA PAGAMENTO", 26, 39, ink);
        rule(canvas, 51);
        ink.setTypeface(Typeface.DEFAULT);
        ink.setTextSize(20);
        canvas.drawText("Fatura IXC: " + safe(id, 25), 26, 82, ink);
        canvas.drawText("Emissão: " + safe(emission, 30), 485, 82, ink);
        canvas.drawText("Vencimento: " + safe(due, 25), 26, 116, ink);
        ink.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        canvas.drawText("Valor: " + safe(amount, 25), 485, 116, ink);
        ink.setTypeface(Typeface.DEFAULT);
        ink.setTextSize(17);
        canvas.drawText("Beneficiário: " + safe(beneficiary, 75), 26, 146, ink);
        rule(canvas, 159);

        // ITF bancário completo (44 dígitos), mais comprido que a bobina.
        BitMatrix bars = new MultiFormatWriter().encode(
                barcode, BarcodeFormat.ITF, LANDSCAPE_WIDTH - 60, 125,
                Collections.singletonMap(EncodeHintType.MARGIN, 12));
        Paint pixel = new Paint();
        pixel.setColor(Color.BLACK);
        // drawBitmap pixel-perfect sem antialias e sem escalonamento.
        for (int x = 0; x < bars.getWidth(); x++) {
            for (int y = 0; y < bars.getHeight(); y++) {
                if (bars.get(x, y)) {
                    canvas.drawPoint(30 + x, 170 + y, pixel);
                }
            }
        }

        ink.setTypeface(Typeface.MONOSPACE);
        ink.setTextSize(21);
        canvas.drawText(barcode.substring(0, 22), 44, 324, ink);
        canvas.drawText(barcode.substring(22), 498, 324, ink);
        ink.setTypeface(Typeface.DEFAULT);
        ink.setTextSize(15);
        canvas.drawText(
                "Confira beneficiário, valor e vencimento no boleto completo antes de pagar.",
                26, 354, ink
        );

        // Resultado final na largura nativa da impressora, sem distorção.
        Matrix rotate = new Matrix();
        rotate.postRotate(90);
        Bitmap paper = Bitmap.createBitmap(
                landscape, 0, 0,
                landscape.getWidth(), landscape.getHeight(), rotate, false);
        landscape.recycle();
        if (paper.getWidth() != PAPER_WIDTH || paper.getHeight() != LANDSCAPE_WIDTH) {
            paper.recycle();
            throw new IllegalStateException("Orientação térmica inválida");
        }
        return paper;
    }

    private static void rule(Canvas c, int y) {
        Paint p = new Paint();
        p.setColor(Color.BLACK);
        p.setStrokeWidth(2f);
        c.drawLine(26, y, LANDSCAPE_WIDTH - 26, y, p);
    }

    private static String safe(String value, int maxLen) {
        if (value == null || value.isBlank()) return "Não informado pelo IXC";
        String clean = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return clean.length() <= maxLen ? clean : clean.substring(0, maxLen);
    }
}
