package br.com.ismtelecom.pos;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Chave de terminal guardada cifrada via Android Keystore, sem credenciais no APK. */
final class TerminalKeyStore {
    private static final String PROVIDER = "AndroidKeyStore";
    private static final String ALIAS = "ism_terminal_v1";
    private static final String PREFS = "ism_terminal_config";
    private static final String SEALED = "terminal_key_gcm";
    private static final int IV_BYTES = 12;

    private TerminalKeyStore() {}

    static String read(Context context) {
        String encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(SEALED, "");
        if (encoded == null || encoded.isEmpty()) return "";
        try {
            byte[] data = Base64.decode(encoded, Base64.NO_WRAP);
            if (data.length <= IV_BYTES + 16) return "";
            byte[] iv = Arrays.copyOfRange(data, 0, IV_BYTES);
            byte[] encrypted = Arrays.copyOfRange(data, IV_BYTES, data.length);
            KeyStore store = KeyStore.getInstance(PROVIDER);
            store.load(null);
            SecretKey key = (SecretKey) store.getKey(ALIAS, null);
            if (key == null) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return "";
        }
    }

    static void save(Context context, String value) throws IOException {
        if (value == null || value.length() < 32 || value.length() > 512)
            throw new IOException("Chave inválida");
        try {
            KeyStore store = KeyStore.getInstance(PROVIDER);
            store.load(null);
            SecretKey key = (SecretKey) store.getKey(ALIAS, null);
            if (key == null) {
                KeyGenerator generator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
                generator.init(new KeyGenParameterSpec.Builder(
                        ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build());
                key = generator.generateKey();
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] output = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, output, 0, iv.length);
            System.arraycopy(encrypted, 0, output, iv.length, encrypted.length);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(SEALED, Base64.encodeToString(output, Base64.NO_WRAP)).apply();
        } catch (Exception error) {
            throw new IOException("Não foi possível proteger a chave no dispositivo", error);
        }
    }

    static void clear(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(SEALED).apply();
    }
}
