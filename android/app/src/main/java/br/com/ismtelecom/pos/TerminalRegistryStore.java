package br.com.ismtelecom.pos;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.IOException;
import java.util.UUID;

/**
 * Identidade de cadastro do terminal ISM, independente do SDK Gertec.
 *
 * O QR compartilhado autentica o provisionamento; o servidor concede o
 * número sequencial ISM-0001 a este UUID privado de instalação.
 * Nunca existe campo de edição do número no aplicativo.
 */
final class TerminalRegistryStore {
    private static final String PREFS = "ism_registry_v1";
    private static final String DEVICE_ID = "installation_uuid";
    private static final String TERMINAL_ID = "assigned_terminal_id";

    private TerminalRegistryStore() {}

    static synchronized String installationId(Context context) throws IOException {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String current = prefs.getString(DEVICE_ID, "");
        if (current != null && current.matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
            return current;
        }
        String generated = UUID.randomUUID().toString();
        if (!prefs.edit().putString(DEVICE_ID, generated).commit()) {
            throw new IOException("Não foi possível registrar esta instalação");
        }
        return generated;
    }

    static String assignedNumber(Context context) {
        String number = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(TERMINAL_ID, "");
        return number != null && number.matches("ISM-[0-9]{4,12}") ? number : "";
    }

    static synchronized void saveNumberOnce(Context context, String terminalId)
            throws IOException {
        if (terminalId == null || !terminalId.matches("ISM-[0-9]{4,12}")) {
            throw new IOException("Número do terminal retornado pelo servidor é inválido");
        }
        String existing = assignedNumber(context);
        if (!existing.isEmpty()) {
            if (!existing.equals(terminalId)) {
                throw new IOException("A identificação já é permanente neste terminal");
            }
            return;
        }
        boolean saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(TERMINAL_ID, terminalId).commit();
        if (!saved) throw new IOException("Não foi possível salvar a identificação ISM");
    }
}
