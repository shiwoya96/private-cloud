package com.privatecloud.app.backup;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;

/** Streams nonce-prefixed AES-GCM ciphertext without buffering a complete file. */
final class EncryptedObjectInputStream extends InputStream {
    private final byte[] nonce;
    private int nonceOffset;
    private final CipherInputStream ciphertext;

    EncryptedObjectInputStream(InputStream plaintext, byte[] key, String objectId)
            throws IOException {
        nonce = RecoveryKeyCrypto.randomNonce();
        try {
            Cipher cipher = RecoveryKeyCrypto.cipher(Cipher.ENCRYPT_MODE, key, nonce, objectId);
            ciphertext = new CipherInputStream(plaintext, cipher);
        } catch (GeneralSecurityException failure) {
            throw new IOException("Unable to initialize snapshot encryption", failure);
        }
    }

    static long encryptedLength(long plaintextLength) {
        if (plaintextLength < 0L) return -1L;
        if (plaintextLength > Long.MAX_VALUE - RecoveryKeyCrypto.NONCE_BYTES
                - RecoveryKeyCrypto.TAG_BYTES) return -1L;
        return plaintextLength + RecoveryKeyCrypto.NONCE_BYTES + RecoveryKeyCrypto.TAG_BYTES;
    }

    @Override public int read() throws IOException {
        if (nonceOffset < nonce.length) return nonce[nonceOffset++] & 0xff;
        return ciphertext.read();
    }

    @Override public int read(byte[] target, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (nonceOffset < nonce.length) {
            int count = Math.min(length, nonce.length - nonceOffset);
            System.arraycopy(nonce, nonceOffset, target, offset, count);
            nonceOffset += count;
            return count;
        }
        return ciphertext.read(target, offset, length);
    }

    @Override public void close() throws IOException { ciphertext.close(); }
}
