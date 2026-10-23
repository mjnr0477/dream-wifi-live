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
 * Stores source and receiver credentials encrypted with Android Keystore AES-GCM keys.
 * Plaintext tokens are never written to preferences or logs.
 */
final class DreamWifiCredentialStore {
    private static final String SOURCE_PREFS = "dream_wifi_source_credentials_v1";
    private static final String RECEIVER_PREFS = "dream_wifi_receiver_credentials_v1";
    private static final String IV_KEY = "iv";
    private static final String DATA_KEY = "ciphertext";
    private static final String SOURCE_KEY_ALIAS = "dream_wifi_source_credentials_aes_v1";
    private static final String RECEIVER_KEY_ALIAS = "dream_wifi_receiver_credentials_aes_v1";
    private static final int GCM_TAG_BITS = 128;

    static final class SourceCredentials {
        final String sourceId;
        final String sourceToken;

        SourceCredentials(String sourceId, String sourceToken) {
            this.sourceId = sourceId;
            this.sourceToken = sourceToken;
        }
    }

    static final class ReceiverCredentials {
        final String sourceId;
        final String accessToken;

        ReceiverCredentials(String sourceId, String accessToken) {
            this.sourceId = sourceId;
            this.accessToken = accessToken;
        }
    }

    private DreamWifiCredentialStore() {}

    static synchronized void saveSource(Context context, String sourceId, String sourceToken) throws Exception {
        validate(sourceId, sourceToken);
        JSONObject payload = new JSONObject();
        payload.put("source_id", sourceId);
        payload.put("source_token", sourceToken);
        saveEncrypted(context, SOURCE_PREFS, SOURCE_KEY_ALIAS, payload);
    }

    static synchronized SourceCredentials loadSource(Context context) {
        JSONObject payload = loadEncrypted(context, SOURCE_PREFS, SOURCE_KEY_ALIAS);
        if (payload == null) return null;
        String sourceId = payload.optString("source_id", "");
        String sourceToken = payload.optString("source_token", "");
        if (!validSourceId(sourceId) || sourceToken.isEmpty()) {
            clearSource(context);
            return null;
        }
        return new SourceCredentials(sourceId, sourceToken);
    }

    static synchronized void clearSource(Context context) {
        clearEncrypted(context, SOURCE_PREFS, SOURCE_KEY_ALIAS);
    }

    static synchronized void saveReceiver(Context context, String sourceId, String accessToken) throws Exception {
        validate(sourceId, accessToken);
        JSONObject payload = new JSONObject();
        payload.put("source_id", sourceId);
        payload.put("access_token", accessToken);
        saveEncrypted(context, RECEIVER_PREFS, RECEIVER_KEY_ALIAS, payload);
    }

    static synchronized ReceiverCredentials loadReceiver(Context context) {
        JSONObject payload = loadEncrypted(context, RECEIVER_PREFS, RECEIVER_KEY_ALIAS);
        if (payload == null) return null;
        String sourceId = payload.optString("source_id", "");
        String accessToken = payload.optString("access_token", "");
        if (!validSourceId(sourceId) || accessToken.isEmpty()) {
            clearReceiver(context);
            return null;
        }
        return new ReceiverCredentials(sourceId, accessToken);
    }

    static synchronized void clearReceiver(Context context) {
        clearEncrypted(context, RECEIVER_PREFS, RECEIVER_KEY_ALIAS);
    }

    private static void validate(String sourceId, String token) {
        if (!validSourceId(sourceId) || token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid Dream Wi-Fi credentials.");
        }
    }

    private static boolean validSourceId(String sourceId) {
        return sourceId != null && sourceId.matches("[A-Za-z0-9_-]{1,80}");
    }

    private static void saveEncrypted(Context context, String preferencesName, String keyAlias, JSONObject payload) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(keyAlias));
        byte[] encrypted = cipher.doFinal(payload.toString().getBytes(StandardCharsets.UTF_8));
        String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
        String ciphertext = Base64.encodeToString(encrypted, Base64.NO_WRAP);
        boolean saved = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit()
                .putString(IV_KEY, iv)
                .putString(DATA_KEY, ciphertext)
                .commit();
        if (!saved) throw new IllegalStateException("Android could not persist encrypted credentials.");
    }

    private static JSONObject loadEncrypted(Context context, String preferencesName, String keyAlias) {
        SharedPreferences preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE);
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
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(keyAlias), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new JSONObject(new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8));
        } catch (Exception invalidOrUnavailable) {
            clearEncrypted(context, preferencesName, keyAlias);
            return null;
        }
    }

    private static void clearEncrypted(Context context, String preferencesName, String keyAlias) {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit();
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(keyAlias)) keyStore.deleteEntry(keyAlias);
        } catch (Exception ignored) {
            // The encrypted preference payload is still cleared; no credential material is logged.
        }
    }

    private static SecretKey getOrCreateKey(String keyAlias) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        if (keyStore.containsAlias(keyAlias)) {
            java.security.Key existing = keyStore.getKey(keyAlias, null);
            if (existing instanceof SecretKey) return (SecretKey) existing;
            keyStore.deleteEntry(keyAlias);
        }

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec specification = new KeyGenParameterSpec.Builder(keyAlias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build();
        generator.init(specification);
        return generator.generateKey();
    }
}
