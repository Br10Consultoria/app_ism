package br.com.ismtelecom.pos;

import android.content.Context;
import android.provider.Settings;

import br.com.gertec.gedi.GEDI;
import br.com.gertec.gedi.enums.GEDI_INFO_e_ControlNumber;
import br.com.gertec.gedi.exceptions.GediException;
import br.com.gertec.gedi.interfaces.IGEDI;
import br.com.gertec.gedi.interfaces.IINFO;

/** Serial de fábrica GPOS780 obtido pelo GEDI 2.2.6; ID Android é auxiliar. */
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

    static Identity read(Context context) {
        String androidId = "";
        try {
            androidId = Settings.Secure.getString(
                    context.getContentResolver(), Settings.Secure.ANDROID_ID);
            if (androidId == null) androidId = "";
            androidId = androidId.trim();
            if (!androidId.matches("[A-Za-z0-9_-]{8,64}")) androidId = "";
        } catch (RuntimeException ignored) {
            // Não bloquear a UI caso o Android não permita o ID auxiliar.
        }

        try {
            Context application = context.getApplicationContext();
            if (application == null) application = context;
            GEDI.init(application);
            IGEDI gedi = GEDI.getInstance(application);
            if (gedi == null) {
                return new Identity("", androidId, "SDK GEDI indisponível");
            }
            IINFO info = gedi.getINFO();
            if (info == null) {
                return new Identity("", androidId, "Módulo INFO não disponível");
            }
            String serial = info.ControlNumberGet(GEDI_INFO_e_ControlNumber.SN);
            serial = serial == null ? "" : serial.trim();
            if (serial.length() < 4 || serial.length() > 64
                    || !serial.matches("[A-Za-z0-9._-]{4,64}")
                    || serial.equalsIgnoreCase("unknown")
                    || serial.equalsIgnoreCase("null")) {
                return new Identity("", androidId, "Serial não retornado pelo GEDI");
            }
            return new Identity(serial, androidId,
                    "Nº de série físico informado pelo GEDI");
        } catch (GediException error) {
            return new Identity("", androidId, "GEDI não autorizou a consulta do serial");
        } catch (LinkageError error) {
            return new Identity("", androidId, "Biblioteca GEDI incompatível");
        } catch (RuntimeException error) {
            return new Identity("", androidId, "Falha na consulta ao GEDI");
        }
    }
}
