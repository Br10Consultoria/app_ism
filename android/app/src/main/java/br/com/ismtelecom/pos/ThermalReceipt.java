package br.com.ismtelecom.pos;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.util.Collections;

/**
 * Layout compacto para a bobina térmica GPOS780, 384 dots de largura.
 * Não abre PDF nem aciona o PrintManager do Android.
 * Os campos financeiros são SOMENTE os retornados pelo IXC; nada é calculado.
 */
final class ThermalReceipt {
    private static final int WIDTH = 384;
    private static final int HEIGHT = 670;

    private ThermalReceipt() {}

    static Bitmap render(String id, String emission, String due, String amount, String barcode)
            throws Exception {
        if (barcode == null || !barcode.matches("[0-9]{44}")) {
            throw new IllegalArgumentException("Código bancário de 44 dígitos inválido");
        }

        Bitmap bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        c.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.BLACK);
        paint.setTextSize(23);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

        int y = 46;
        centered(c, paint, "ISM TELECOM", y); y += 31;
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(18);
        centered(c, paint, "BOLETO - DADOS PARA PAGAMENTO", y); y += 36;
        rule(c, y); y += 36;

        paint.setTextSize(22);
        paint.setTypeface(Typeface.DEFAULT);
        c.drawText("Título IXC: " + safe(id, 18), 12, y, paint); y += 31;
        c.drawText("Emissão: " + safe(emission, 22), 12, y, paint); y += 31;
        c.drawText("Vencimento: " + safe(due, 18), 12, y, paint); y += 31;

        paint.setTextSize(24);
        paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        c.drawText("Valor cobrado: " + safe(amount, 17), 12, y, paint); y += 42;
        rule(c, y); y += 27;

        paint.setTextSize(17);
        paint.setTypeface(Typeface.DEFAULT);
        centered(c, paint, "CÓDIGO DE BARRAS", y); y += 15;

        // ITF: mantém pixels binários, sem redimensionamento que borre barras.
        BitMatrix bits = new MultiFormatWriter().encode(
                barcode, BarcodeFormat.ITF, 368, 120,
                Collections.singletonMap(EncodeHintType.MARGIN, 6)
        );
        Paint pixel = new Paint();
        pixel.setColor(Color.BLACK);
        for (int x = 0; x < bits.getWidth(); x++) {
            for (int row = 0; row < bits.getHeight(); row++) {
                if (bits.get(x, row)) {
                    c.drawPoint(8 + x, y + row, pixel);
                }
            }
        }
        y += 137;
        paint.setTypeface(Typeface.MONOSPACE);
        paint.setTextSize(21);
        centered(c, paint, barcode.substring(0, 22), y); y += 27;
        centered(c, paint, barcode.substring(22), y); y += 31;
        rule(c, y); y += 25;
        paint.setTypeface(Typeface.DEFAULT);
        paint.setTextSize(15);
        centered(c, paint, "Confirme beneficiário, valor e", y); y += 21;
        centered(c, paint, "vencimento antes de pagar.", y);

        return bitmap;
    }

    private static void centered(Canvas c, Paint p, String value, int y) {
        c.drawText(value, Math.max(6, (WIDTH - p.measureText(value)) / 2f), y, p);
    }

    private static void rule(Canvas c, int y) {
        Paint p = new Paint();
        p.setColor(Color.BLACK);
        p.setStrokeWidth(2);
        c.drawLine(10, y, WIDTH - 10, y, p);
    }

    private static String safe(String value, int maxLen) {
        if (value == null || value.isBlank()) return "Não informado";
        String clean = value.replaceAll("[\\r\\n\\t]", " ").trim();
        return clean.length() <= maxLen ? clean : clean.substring(0, maxLen);
    }
}
