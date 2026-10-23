package com.dreameducation.wifi;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores source enrollment credentials encrypted with an Android Keystore AES-GCM key.
 * The plaintext token is never written to preferences or logs.
 */
final class DreamWifiCredentialStore {
    private static final String PREFS = "dream_wifi_source_credentials_v1";
    private static final String IV_KEY = "iv";
    private static final String DATA_KEY = "ciphertext";
    private static final String KEY_ALIAS = "dream_wifi_source_credentials_aes_v1";
    private static final int GCM_TAG_BITS = 128;

    static final class SourceCredentials {
        final String sourceId;
        final String sourceToken;

        SourceCredentials(String sourceId, String sourceToken) {
            this.sourceId = sourceId;
            this.sourceToken = sourceToken;
        }
    }

    private DreamWifiCredentialStore() {}

    static synchronized void saveSource(Context context, String sourceId, String sourceToken) throws Exception {
        if (sourceId == null || !sourceId.matches("[A-Za-z0-9_-]{1,80}")
                || sourceToken == null || sourceToken.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid source credentials.");
        }
        JSONObject payload = new JSONObject();
        payload.put("source_id", sourceId);
        payload.put("source_token", sourceToken);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8));
        String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
        String ciphertext = Base64.encodeToString(encrypted, Base64.NO_WRAP);
        boolean saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(IV_KEY, iv)
                .putString(DATA_KEY, ciphertext)
                .commit();
        if (!saved) throw new IllegalStateException("Android could not persist the encrypted source credential.");
    }

    static synchronized SourceCredentials loadSource(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String ivValue = preferences.getString(IV_KEY, "");
        String dataValue = preferences.getString(DATA_KEY, "");
        if (ivValue == null || ivValue.isEmpty() || dataValue == null || dataValue.isEmpty()) return null;

        try {
            byte[] iv = Base64.decode(ivValue, Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(dataValue, Base64.NO_WRAP);
            if (iv.length != 12 || ciphertext.length < 16 || ciphertext.length > 4096) {
                throw new IllegalArgumentException("Stored credential payload is invalid.");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            JSONObject payload = new JSONObject(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
            String sourceId = payload.optString("source_id", "");
            String sourceToken = payload.optString("source_token", "");
            if (!sourceId.matches("[A-Za-z0-9_-]{1,80}") || sourceToken.isEmpty()) {
                throw new IllegalArgumentException("Stored source credentials are incomplete.");
            }
            return new SourceCredentials(sourceId, sourceToken);
        } catch (Exception invalidOrUnavailable) {
            clearSource(context);
            return null;
        }
    }

    static synchronized void clearSource(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
            // No credential material is logged; the preference payload is still cleared.
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
            if (existing instanceof SecretKey) return (SecretKey) existing;
            keyStore.deleteEntry(KEY_ALIAS);
        }

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec specification = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build();
        generator.init(specification);
        return generator.generateKey();
    }
}
