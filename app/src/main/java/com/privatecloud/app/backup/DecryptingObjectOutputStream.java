package com.privatecloud.app.backup;

import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;

/** Accepts nonce-prefixed ciphertext and writes authenticated plaintext to its destination. */
final class DecryptingObjectOutputStream extends OutputStream {
    private final OutputStream plaintext;
    private final byte[] key;
    private final String objectId;
    private final byte[] nonce = new byte[RecoveryKeyCrypto.NONCE_BYTES];
    private int nonceCount;
    private Cipher cipher;
    private boolean finished;

    DecryptingObjectOutputStream(OutputStream plaintext, byte[] key, String objectId) {
        this.plaintext = plaintext;
        this.key = key;
        this.objectId = objectId;
    }

    @Override public void write(int value) throws IOException {
        byte[] single = {(byte) value};
        write(single, 0, 1);
    }

    @Override public void write(byte[] source, int offset, int length) throws IOException {
        if (finished) throw new IOException("Encrypted object is already finalized");
        while (length > 0 && nonceCount < nonce.length) {
            int count = Math.min(length, nonce.length - nonceCount);
            System.arraycopy(source, offset, nonce, nonceCount, count);
            nonceCount += count;
            offset += count;
            length -= count;
        }
        if (length == 0) return;
        ensureCipher();
        byte[] decoded = cipher.update(source, offset, length);
        if (decoded != null) plaintext.write(decoded);
    }

    void finish() throws IOException {
        if (finished) return;
        finished = true;
        ensureCipher();
        try {
            byte[] decoded = cipher.doFinal();
            if (decoded != null) plaintext.write(decoded);
        } catch (AEADBadTagException invalidKeyOrData) {
            throw new InvalidSnapshotException(
                    "Snapshot object authentication failed; check the recovery key",
                    invalidKeyOrData);
        } catch (GeneralSecurityException failure) {
            throw new IOException("Unable to decrypt snapshot object", failure);
        }
    }

    private void ensureCipher() throws IOException {
        if (nonceCount != nonce.length) throw new InvalidSnapshotException("Encrypted object is truncated");
        if (cipher != null) return;
        try {
            cipher = RecoveryKeyCrypto.cipher(Cipher.DECRYPT_MODE, key, nonce, objectId);
        } catch (GeneralSecurityException failure) {
            throw new IOException("Unable to initialize snapshot decryption", failure);
        }
    }
}
