package com.privatecloud.app.backup;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/** Cancellation-aware, constant-memory transfer streams with incremental SHA-256. */
final class TransferStreams {
    private TransferStreams() {}

    interface ByteObserver {
        void onBytes(long fileBytes);
    }

    static final ByteObserver NO_OBSERVER = new ByteObserver() {
        @Override
        public void onBytes(long fileBytes) {}
    };

    static final class DigestingInputStream extends FilterInputStream {
        private final CancellationToken cancellation;
        private final ByteObserver observer;
        private final MessageDigest digest = SnapshotFormat.newSha256();
        private long count;
        private boolean reachedEnd;

        DigestingInputStream(
                InputStream input,
                CancellationToken cancellation,
                ByteObserver observer) {
            super(input);
            this.cancellation = cancellation;
            this.observer = observer == null ? NO_OBSERVER : observer;
        }

        @Override
        public int read() throws IOException {
            cancellation.throwIfCancellationRequested();
            int value = super.read();
            if (value == -1) {
                reachedEnd = true;
                return -1;
            }
            digest.update((byte) value);
            increment(1L);
            return value;
        }

        @Override
        public int read(byte[] destination, int offset, int length) throws IOException {
            cancellation.throwIfCancellationRequested();
            int read = super.read(destination, offset, length);
            if (read == -1) {
                reachedEnd = true;
                return -1;
            }
            if (read > 0) {
                digest.update(destination, offset, read);
                increment(read);
            }
            return read;
        }

        @Override
        public long skip(long byteCount) throws IOException {
            if (byteCount <= 0L) {
                return 0L;
            }
            byte[] scratch = new byte[(int) Math.min(8192L, byteCount)];
            long skipped = 0L;
            while (skipped < byteCount) {
                int read = read(scratch, 0, (int) Math.min(scratch.length, byteCount - skipped));
                if (read == -1) {
                    break;
                }
                skipped += read;
            }
            return skipped;
        }

        long getCount() {
            return count;
        }

        boolean reachedEnd() {
            return reachedEnd;
        }

        String finishSha256() throws IOException {
            if (!reachedEnd) {
                throw new IOException("Remote store did not consume the complete source stream");
            }
            return SnapshotFormat.hex(digest.digest());
        }

        private void increment(long amount) throws IOException {
            if (count > Long.MAX_VALUE - amount) {
                throw new IOException("Transferred byte count overflow");
            }
            count += amount;
            observer.onBytes(count);
            cancellation.throwIfCancellationRequested();
        }
    }

    static final class VerifyingOutputStream extends FilterOutputStream {
        private final CancellationToken cancellation;
        private final ByteObserver observer;
        private final MessageDigest digest = SnapshotFormat.newSha256();
        private final long expectedSize;
        private final String expectedSha256;
        private long count;

        VerifyingOutputStream(
                OutputStream output,
                long expectedSize,
                String expectedSha256,
                CancellationToken cancellation,
                ByteObserver observer) {
            super(output);
            if (expectedSize < 0L) {
                throw new IllegalArgumentException("expectedSize must be non-negative");
            }
            this.expectedSize = expectedSize;
            this.expectedSha256 = expectedSha256;
            this.cancellation = cancellation;
            this.observer = observer == null ? NO_OBSERVER : observer;
        }

        @Override
        public void write(int value) throws IOException {
            cancellation.throwIfCancellationRequested();
            requireRemaining(1L);
            out.write(value);
            digest.update((byte) value);
            increment(1L);
        }

        @Override
        public void write(byte[] source, int offset, int length) throws IOException {
            if (source == null) {
                throw new NullPointerException("source");
            }
            if (offset < 0 || length < 0 || offset > source.length - length) {
                throw new IndexOutOfBoundsException();
            }
            cancellation.throwIfCancellationRequested();
            requireRemaining(length);
            out.write(source, offset, length);
            digest.update(source, offset, length);
            increment(length);
        }

        void verify() throws InvalidSnapshotException {
            if (count != expectedSize) {
                throw new InvalidSnapshotException(
                        "Snapshot object size mismatch: expected " + expectedSize
                                + ", received " + count);
            }
            String actualHash = SnapshotFormat.hex(digest.digest());
            if (!actualHash.equals(expectedSha256)) {
                throw new InvalidSnapshotException("Snapshot object SHA-256 mismatch");
            }
        }

        private void requireRemaining(long amount) throws InvalidSnapshotException {
            if (amount > expectedSize - count) {
                throw new InvalidSnapshotException(
                        "Snapshot object exceeded its declared size");
            }
        }

        private void increment(long amount) throws IOException {
            count += amount;
            observer.onBytes(count);
            cancellation.throwIfCancellationRequested();
        }
    }
}
