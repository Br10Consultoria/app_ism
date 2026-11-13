package br.com.ismtelecom.pos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/** Primeira marca do PDF oficial ISM Telecom.
 * Arquivo WebP 220x220 incluído em res/drawable; ScaleType.FIT_CENTER
 * preserva a proporção sem desenhar ou inclinar letras. */
final class IsmOfficialLogo {
    private IsmOfficialLogo() {}
    static Bitmap decode(Context context) {
        Bitmap logo = BitmapFactory.decodeResource(context.getResources(),
                R.drawable.ism_logo_primeira);
        if (logo == null) throw new IllegalStateException("Logomarca ISM inválida");
        return logo;
    }
}
