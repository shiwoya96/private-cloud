package com.privatecloud.app.backup;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Recovery-key parsing and per-object AES-256-GCM primitives. */
public final class RecoveryKeyCrypto {
    public static final int NONCE_BYTES = 12;
    public static final int TAG_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryKeyCrypto() {}

    public static String generateRecoveryKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    public static byte[] parseRecoveryKey(String encoded) {
        try {
            byte[] key = Base64.getUrlDecoder().decode(encoded == null ? "" : encoded.trim());
            if (key.length != 32) throw new IllegalArgumentException("恢复密钥必须为 256 位");
            return key;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("恢复密钥格式无效", invalid);
        }
    }

    static byte[] randomNonce() {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return nonce;
    }

    static Cipher cipher(int mode, byte[] recoveryKey, byte[] nonce, String objectId)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(recoveryKey, "AES"),
                new GCMParameterSpec(128, nonce));
        cipher.updateAAD(objectId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return cipher;
    }

    public static String fingerprint(String encoded) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(parseRecoveryKey(encoded));
            StringBuilder value = new StringBuilder();
            for (int index = 0; index < 6; index++) value.append(String.format("%02X", digest[index]));
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
