package com.privatecloud.app.backup;

import com.privatecloud.app.model.SnapshotInfo;
import com.privatecloud.app.remote.RemoteStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** JSON commit marker, hashes, and bounded remote reads shared by snapshot operations. */
final class SnapshotFormat {
    static final int COMPLETE_SCHEMA = 1;
    static final int MAX_COMPLETE_BYTES = 64 * 1024;

    private SnapshotFormat() {}

    static byte[] encodeComplete(SnapshotInfo info) throws IOException {
        try {
            JSONObject complete = new JSONObject();
            complete.put("schema", COMPLETE_SCHEMA);
            complete.put("planId", info.getPlanId());
            complete.put("snapshotId", info.getSnapshotId());
            complete.put("createdAt", info.getCreatedAt());
            complete.put("sourceName", info.getSourceName());
            complete.put("manifest", SnapshotLayout.MANIFEST_NAME);
            complete.put("manifestSize", info.getManifestSize());
            complete.put("manifestSha256", info.getManifestSha256());
            complete.put("fileCount", info.getFileCount());
            complete.put("directoryCount", info.getDirectoryCount());
            complete.put("totalBytes", info.getTotalBytes());
            byte[] encoded = complete.toString().getBytes(StandardCharsets.UTF_8);
            if (encoded.length > MAX_COMPLETE_BYTES) {
                throw new IOException("Snapshot commit marker is unexpectedly large");
            }
            return encoded;
        } catch (JSONException impossible) {
            throw new IOException("Unable to serialize snapshot commit marker", impossible);
        }
    }

    static SnapshotInfo parseComplete(byte[] bytes) throws InvalidSnapshotException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_COMPLETE_BYTES) {
            throw new InvalidSnapshotException("Invalid snapshot commit marker length");
        }
        try {
            JSONObject complete = decodeObject(bytes);
            long schema = requireLong(complete, "schema");
            if (schema != COMPLETE_SCHEMA) {
                throw new InvalidSnapshotException(
                        "Unsupported snapshot commit schema: " + schema);
            }
            if (!SnapshotLayout.MANIFEST_NAME.equals(requireString(complete, "manifest"))) {
                throw new InvalidSnapshotException("Commit marker references an invalid manifest");
            }
            return new SnapshotInfo(
                    requireString(complete, "planId"),
                    requireString(complete, "snapshotId"),
                    requireLong(complete, "createdAt"),
                    requireString(complete, "sourceName"),
                    requireLong(complete, "fileCount"),
                    requireLong(complete, "directoryCount"),
                    requireLong(complete, "totalBytes"),
                    requireLong(complete, "manifestSize"),
                    requireString(complete, "manifestSha256").toLowerCase(Locale.US));
        } catch (JSONException malformed) {
            throw new InvalidSnapshotException("Invalid snapshot commit JSON", malformed);
        } catch (CharacterCodingException malformedUtf8) {
            throw new InvalidSnapshotException(
                    "Snapshot commit marker is not valid UTF-8", malformedUtf8);
        } catch (IllegalArgumentException invalid) {
            throw new InvalidSnapshotException("Invalid snapshot commit marker", invalid);
        }
    }

    static byte[] downloadBytes(
            RemoteStore store,
            String path,
            int maximumBytes,
            CancellationToken cancellationToken) throws IOException {
        CancellationToken cancellation = cancellationToken == null
                ? CancellationToken.NONE : cancellationToken;
        LimitedOutputStream destination = new LimitedOutputStream(maximumBytes, cancellation);
        cancellation.throwIfCancellationRequested();
        store.download(path, destination);
        cancellation.throwIfCancellationRequested();
        return destination.toByteArray();
    }

    static void uploadNewBytes(
            RemoteStore store,
            String path,
            byte[] bytes,
            CancellationToken cancellation) throws IOException {
        CancellationToken safeCancellation = cancellation == null
                ? CancellationToken.NONE : cancellation;
        try (TransferStreams.DigestingInputStream input =
                new TransferStreams.DigestingInputStream(
                        new ByteArrayInputStream(bytes),
                        safeCancellation,
                        TransferStreams.NO_OBSERVER)) {
            store.upload(path, input, bytes.length, false);
            input.finishSha256();
        }
    }

    static String sha256(byte[] bytes) {
        MessageDigest digest = newSha256();
        digest.update(bytes);
        return hex(digest.digest());
    }

    static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("SHA-256 is unavailable", impossible);
        }
    }

    static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] encoded = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            encoded[index * 2] = alphabet[value >>> 4];
            encoded[index * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(encoded);
    }

    private static String requireString(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof String)) {
            throw new JSONException(key + " must be a string");
        }
        return (String) value;
    }

    private static long requireLong(JSONObject object, String key) throws JSONException {
        Object value = object.get(key);
        if (!(value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long)) {
            throw new JSONException(key + " must be an integer");
        }
        return ((Number) value).longValue();
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes));
        return decoded.toString();
    }

    /** Parses exactly one JSON object; Android JSONTokener uses '\0' as its EOF sentinel. */
    private static JSONObject decodeObject(byte[] bytes)
            throws CharacterCodingException, JSONException {
        JSONTokener tokens = new JSONTokener(decodeUtf8(bytes));
        Object value = tokens.nextValue();
        if (!(value instanceof JSONObject) || tokens.nextClean() != '\0') {
            throw new JSONException("Expected exactly one JSON object");
        }
        return (JSONObject) value;
    }

    private static final class LimitedOutputStream extends OutputStream {
        private final int maximumBytes;
        private final CancellationToken cancellation;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int count;

        LimitedOutputStream(int maximumBytes, CancellationToken cancellation) {
            if (maximumBytes <= 0) {
                throw new IllegalArgumentException("maximumBytes must be positive");
            }
            this.maximumBytes = maximumBytes;
            this.cancellation = cancellation;
        }

        @Override
        public void write(int value) throws IOException {
            cancellation.throwIfCancellationRequested();
            ensureCapacity(1);
            bytes.write(value);
            count++;
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
            ensureCapacity(length);
            bytes.write(source, offset, length);
            count += length;
        }

        byte[] toByteArray() {
            return bytes.toByteArray();
        }

        private void ensureCapacity(int additional) throws IOException {
            if (additional > maximumBytes - count) {
                throw new InvalidSnapshotException(
                        "Remote snapshot metadata exceeds " + maximumBytes + " bytes");
            }
        }
    }
}
