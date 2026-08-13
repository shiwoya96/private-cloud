package com.privatecloud.app.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts small secrets with a non-exportable Android Keystore key. */
final class CredentialVault {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "private_cloud_credentials_v1";
    private static final String PREFS = "credential_ciphertexts_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int TAG_BITS = 128;

    private final SharedPreferences legacyPreferences;

    CredentialVault(Context context) {
        legacyPreferences = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * Returns a self-contained IV/ciphertext value. The caller decides whether an atomic
     * preference transaction or WorkManager Data owns the encrypted envelope.
     */
    synchronized String encrypt(String name, String value) throws GeneralSecurityException {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("Missing secret name");
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        cipher.updateAAD(aad(name));
        byte[] ciphertext = cipher.doFinal(safe(value).getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                + "." + Base64.encodeToString(ciphertext, Base64.NO_WRAP);
    }

    synchronized String decrypt(String name, String encoded) throws GeneralSecurityException {
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("Missing secret name");
        if (encoded == null || encoded.isEmpty()) {
            throw new GeneralSecurityException("Stored credential is missing");
        }
        int separator = encoded.indexOf('.');
        if (separator <= 0 || separator == encoded.length() - 1) {
            throw new GeneralSecurityException("Stored credential is damaged");
        }
        try {
            byte[] iv = Base64.decode(encoded.substring(0, separator), Base64.NO_WRAP);
            byte[] ciphertext = Base64.decode(encoded.substring(separator + 1), Base64.NO_WRAP);
            if (iv.length != 12) throw new GeneralSecurityException("Invalid credential IV");
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(name));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            throw new GeneralSecurityException("Stored credential is damaged", malformed);
        }
    }

    /** Reads credentials written by the pre-snapshot storage layout for one-time migration. */
    synchronized String getLegacyCiphertext(String name) {
        return legacyPreferences.getString(name, null);
    }

    synchronized void removeLegacyCiphertext(String name) {
        legacyPreferences.edit().remove(name).commit();
    }

    private static synchronized SecretKey getOrCreateKey() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        try {
            keyStore.load(null);
        } catch (java.io.IOException impossible) {
            throw new GeneralSecurityException("Unable to load Android Keystore", impossible);
        } catch (java.security.cert.CertificateException impossible) {
            throw new GeneralSecurityException("Unable to load Android Keystore", impossible);
        }
        java.security.Key key = keyStore.getKey(ALIAS, null);
        if (key instanceof SecretKey) return (SecretKey) key;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static byte[] aad(String name) {
        return ("private-cloud|v1|" + name).getBytes(StandardCharsets.UTF_8);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
