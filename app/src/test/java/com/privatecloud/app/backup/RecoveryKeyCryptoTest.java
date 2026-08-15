package com.privatecloud.app.backup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public final class RecoveryKeyCryptoTest {
    @Test public void generatedKeysRoundTripAndAreUnique() {
        String first = RecoveryKeyCrypto.generateRecoveryKey();
        String second = RecoveryKeyCrypto.generateRecoveryKey();
        assertEquals(32, RecoveryKeyCrypto.parseRecoveryKey(first).length);
        assertNotEquals(first, second);
        assertEquals(12, RecoveryKeyCrypto.fingerprint(first).length());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsShortKey() {
        RecoveryKeyCrypto.parseRecoveryKey("c2hvcnQ");
    }

    @Test public void objectStreamEncryptsAndAuthenticates() throws Exception {
        byte[] plaintext = "私有云 encrypted backup".getBytes(StandardCharsets.UTF_8);
        byte[] key = RecoveryKeyCrypto.parseRecoveryKey(
                RecoveryKeyCrypto.generateRecoveryKey());
        ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        try (EncryptedObjectInputStream input = new EncryptedObjectInputStream(
                new ByteArrayInputStream(plaintext), key, "object-id")) {
            byte[] buffer = new byte[7];
            int read;
            while ((read = input.read(buffer)) != -1) encrypted.write(buffer, 0, read);
        }
        assertEquals(plaintext.length + 28, encrypted.size());

        ByteArrayOutputStream restored = new ByteArrayOutputStream();
        DecryptingObjectOutputStream output = new DecryptingObjectOutputStream(
                restored, key, "object-id");
        byte[] ciphertext = encrypted.toByteArray();
        for (int offset = 0; offset < ciphertext.length; offset += 5) {
            output.write(ciphertext, offset, Math.min(5, ciphertext.length - offset));
        }
        output.finish();
        assertEquals(new String(plaintext, StandardCharsets.UTF_8),
                restored.toString(StandardCharsets.UTF_8.name()));
    }

    @Test public void tamperingIsRejected() throws Exception {
        byte[] key = RecoveryKeyCrypto.parseRecoveryKey(RecoveryKeyCrypto.generateRecoveryKey());
        ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        try (EncryptedObjectInputStream input = new EncryptedObjectInputStream(
                new ByteArrayInputStream(new byte[] {1, 2, 3}), key, "object-id")) {
            int value;
            while ((value = input.read()) != -1) encrypted.write(value);
        }
        byte[] damaged = encrypted.toByteArray();
        damaged[damaged.length - 1] ^= 1;
        DecryptingObjectOutputStream output = new DecryptingObjectOutputStream(
                new ByteArrayOutputStream(), key, "object-id");
        output.write(damaged);
        try {
            output.finish();
            fail("tampered ciphertext must fail authentication");
        } catch (InvalidSnapshotException expected) {
            // Expected.
        }
    }
}
