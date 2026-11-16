package br.com.ismtelecom.pos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

/**
 * Mesma identidade visual ISM da primeira logo, removendo o branco externo.
 * A área branca interna da marca permanece: a remoção visita SOMENTE pixels
 * claros conectados às bordas da imagem, preservando o desenho central.
 *
 * É um tratamento da arte já incluída no repo; a arte original fornecida
 * pelo cliente pode substituí-la diretamente em res/drawable.
 */
final class IsmOfficialLogo {
    private IsmOfficialLogo() {}

    static Bitmap decode(Context context) {
        Bitmap source = BitmapFactory.decodeResource(
                context.getResources(), R.drawable.ism_logo_primeira);
        if (source == null) throw new IllegalStateException("Logomarca ISM indisponível");

        Bitmap result = source.copy(Bitmap.Config.ARGB_8888, true);
        int width = result.getWidth();
        int height = result.getHeight();
        int length = width * height;
        int[] pixels = new int[length];
        result.getPixels(pixels, 0, width, 0, 0, width, height);

        boolean[] visited = new boolean[length];
        int[] queue = new int[length];
        int front = 0;
        int back = 0;

        for (int x = 0; x < width; x++) {
            back = enqueue(pixels, visited, queue, back, x);
            back = enqueue(pixels, visited, queue, back, (height - 1) * width + x);
        }
        for (int y = 0; y < height; y++) {
            back = enqueue(pixels, visited, queue, back, y * width);
            back = enqueue(pixels, visited, queue, back, y * width + width - 1);
        }

        while (front < back) {
            int pos = queue[front++];
            int x = pos % width;
            int y = pos / width;
            pixels[pos] = Color.TRANSPARENT;
            if (x > 0) back = enqueue(pixels, visited, queue, back, pos - 1);
            if (x + 1 < width) back = enqueue(pixels, visited, queue, back, pos + 1);
            if (y > 0) back = enqueue(pixels, visited, queue, back, pos - width);
            if (y + 1 < height) back = enqueue(pixels, visited, queue, back, pos + width);
        }

        result.setPixels(pixels, 0, width, 0, 0, width, height);
        if (source != result) source.recycle();
        return result;
    }

    private static int enqueue(int[] pixels, boolean[] visited,
                               int[] queue, int next, int index) {
        if (visited[index]) return next;
        visited[index] = true;
        int rgb = pixels[index];
        if (Color.alpha(rgb) == 0 ||
                (Color.red(rgb) >= 238 && Color.green(rgb) >= 238
                        && Color.blue(rgb) >= 238)) {
            queue[next++] = index;
        }
        return next;
    }
}
