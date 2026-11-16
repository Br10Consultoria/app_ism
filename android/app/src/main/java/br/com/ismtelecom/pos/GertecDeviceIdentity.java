package br.com.ismtelecom.pos;

import android.content.Context;
import android.provider.Settings;
import java.lang.reflect.InvocationTargetException;
import java.util.Locale;

/**
 * Identidade da GPOS780.
 *
 * O número de série físico vem exclusivamente do GEDI/IINFO.ControlNumberGet(SN).
 * ANDROID_ID é identificador da instalação Android, não é número de série.
 * Android 11 limita Build.getSerial() para apps comuns; não inventar serial.
 */
final class GertecDeviceIdentity {
    static final class Identity {
        final String serial;
        final String androidId;
        final String diagnostic;

        Identity(String serial, String androidId, String diagnostic) {
            this.serial = serial;
            this.androidId = androidId;
            this.diagnostic = diagnostic;
        }
    }

    private GertecDeviceIdentity() {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Identity read(Context context) {
        String androidId = "";
        try {
            androidId = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId == null) androidId = "";
            androidId = androidId.trim();
            if (!androidId.matches("[A-Za-z0-9_-]{8,64}")) androidId = "";
        } catch (RuntimeException ignored) {
            // A identificação Android é auxiliar, nunca condição de ativação.
        }

        try {
            Class<?> gedi = Class.forName("br.com.gertec.gedi.GEDI");
            Context application = context.getApplicationContext();
            if (application == null) application = context;
            gedi.getMethod("init", Context.class).invoke(null, application);
            Object igedi = gedi.getMethod("getInstance", Context.class)
                    .invoke(null, application);
            if (igedi == null) {
                return new Identity("", androidId, "GEDI não inicializado");
            }

            Object info = Class.forName("br.com.gertec.gedi.interfaces.IGEDI")
                    .getMethod("getINFO").invoke(igedi);
            if (info == null) {
                return new Identity("", androidId, "GEDI/INFO indisponível");
            }

            Class<?> snEnum = Class.forName(
                    "br.com.gertec.gedi.enums.GEDI_INFO_e_ControlNumber");
            Object sn = Enum.valueOf((Class) snEnum, "SN");
            String serial = (String) Class.forName(
                    "br.com.gertec.gedi.interfaces.IINFO")
                    .getMethod("ControlNumberGet", snEnum)
                    .invoke(info, sn);

            serial = serial == null ? "" : serial.trim();
            if (serial.isBlank() || serial.equalsIgnoreCase("unknown")
                    || serial.equals("0") || serial.equalsIgnoreCase("null")
                    || serial.length() > 64
                    || !serial.matches("[A-Za-z0-9._-]{4,64}")) {
                return new Identity("", androidId, "Serial GEDI não disponível");
            }
            return new Identity(serial, androidId, "Serial de fábrica via Gertec GEDI");
        } catch (ClassNotFoundException | NoClassDefFoundError ex) {
            return new Identity("", androidId,
                    "SDK GEDI não incluído na compilação");
        } catch (InvocationTargetException ex) {
            return new Identity("", androidId,
                    "GEDI não autorizou a consulta do serial");
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            return new Identity("", androidId, "Falha na consulta ao GEDI");
        }
    }
}
