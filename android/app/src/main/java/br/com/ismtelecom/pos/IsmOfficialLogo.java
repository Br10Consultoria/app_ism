package br.com.ismtelecom.pos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/** Logo ISM Telecom original, com transparência real, fornecida pelo cliente. */
final class IsmOfficialLogo {
    private IsmOfficialLogo() {}

    static Bitmap decode(Context context) {
        Bitmap bitmap = BitmapFactory.decodeResource(
                context.getResources(), R.drawable.ism_logo_gpos780);
        if (bitmap == null) {
            throw new IllegalStateException("Recurso da logomarca ISM não encontrado");
        }
        return bitmap;
    }
}
