package com.privatecloud.app.model;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** One immutable directory or file in a snapshot manifest. */
public final class ManifestEntry {
    public enum Type {
        DIRECTORY,
        FILE
    }

    public static final int MAX_DEPTH = 128;
    public static final int MAX_SEGMENT_CHARS = 255;
    public static final int MAX_SEGMENT_UTF8_BYTES = 1024;
    public static final int MAX_PATH_UTF8_BYTES = 16 * 1024;

    private static final Pattern OBJECT_ID = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern MIME_TYPE = Pattern.compile(
            "[A-Za-z0-9][A-Za-z0-9!#$&^_.+%-]{0,126}/"
                    + "[A-Za-z0-9][A-Za-z0-9!#$&^_.+%-]{0,126}");
    private static final String SAF_DIRECTORY_MIME_TYPE = "vnd.android.document/directory";

    private final List<String> pathSegments;
    private final Type type;
    private final String objectId;
    private final long size;
    private final long lastModified;
    private final String mimeType;
    private final String sha256;

    private ManifestEntry(
            List<String> pathSegments,
            Type type,
            String objectId,
            long size,
            long lastModified,
            String mimeType,
            String sha256) {
        this.pathSegments = immutableValidatedPath(pathSegments);
        this.type = Objects.requireNonNull(type, "type");
        this.lastModified = requireNonNegative(lastModified, "lastModified");

        if (type == Type.DIRECTORY) {
            if (objectId != null || sha256 != null || size != 0L || mimeType != null) {
                throw new IllegalArgumentException(
                        "Directory entries cannot contain file metadata");
            }
            this.objectId = null;
            this.size = 0L;
            this.mimeType = null;
            this.sha256 = null;
            return;
        }

        if (objectId == null || !OBJECT_ID.matcher(objectId).matches()) {
            throw new IllegalArgumentException("Invalid snapshot object id");
        }
        if (sha256 == null || !SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("Invalid SHA-256 value");
        }
        if (size < 0L) {
            throw new IllegalArgumentException("File size must be non-negative");
        }
        if (mimeType != null) {
            if (!isSafeFileMimeType(mimeType)) {
                throw new IllegalArgumentException("Invalid file MIME type");
            }
        }
        this.objectId = objectId;
        this.size = size;
        this.mimeType = mimeType;
        this.sha256 = sha256.toLowerCase(Locale.US);
    }

    public static ManifestEntry directory(List<String> pathSegments, long lastModified) {
        return new ManifestEntry(
                pathSegments, Type.DIRECTORY, null, 0L, lastModified, null, null);
    }

    public static ManifestEntry file(
            List<String> pathSegments,
            String objectId,
            long size,
            long lastModified,
            String mimeType,
            String sha256) {
        return new ManifestEntry(
                pathSegments,
                Type.FILE,
                objectId,
                size,
                lastModified,
                emptyToNull(mimeType),
                sha256);
    }

    public List<String> getPathSegments() {
        return pathSegments;
    }

    public Type getType() {
        return type;
    }

    public boolean isDirectory() {
        return type == Type.DIRECTORY;
    }

    public String getObjectId() {
        return objectId;
    }

    public long getSize() {
        return size;
    }

    public long getLastModified() {
        return lastModified;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getSha256() {
        return sha256;
    }

    /** Human-readable relative path. It is never used to access a filesystem or remote store. */
    public String displayPath() {
        StringBuilder value = new StringBuilder();
        for (String segment : pathSegments) {
            if (value.length() > 0) {
                value.append('/');
            }
            value.append(segment);
        }
        return value.toString();
    }

    /** Internal collision key that remains unambiguous for names containing separators. */
    public String pathKey() {
        return pathKey(pathSegments, pathSegments.size());
    }

    /** Collision-free key for a prefix of a segmented path. */
    public static String pathKey(List<String> segments, int segmentCount) {
        if (segments == null || segmentCount < 0 || segmentCount > segments.size()) {
            throw new IllegalArgumentException("Invalid path prefix");
        }
        StringBuilder key = new StringBuilder();
        for (int index = 0; index < segmentCount; index++) {
            String segment = segments.get(index);
            validatePathSegment(segment);
            key.append(segment.length()).append(':').append(segment);
        }
        return key.toString();
    }

    public static void validatePathSegment(String segment) {
        if (segment == null || segment.isEmpty()) {
            throw new IllegalArgumentException("Path segment must not be empty");
        }
        if (segment.equals(".") || segment.equals("..")) {
            throw new IllegalArgumentException("Dot path segments are not allowed");
        }
        if (segment.length() > MAX_SEGMENT_CHARS
                || segment.getBytes(StandardCharsets.UTF_8).length > MAX_SEGMENT_UTF8_BYTES) {
            throw new IllegalArgumentException("Path segment is too long");
        }
        rejectControlCharacters(segment, "path segment");
    }

    /** MIME values cross a trust boundary into DocumentsContract.createDocument(). */
    public static boolean isSafeFileMimeType(String mimeType) {
        return mimeType != null
                && mimeType.length() <= 255
                && !SAF_DIRECTORY_MIME_TYPE.equalsIgnoreCase(mimeType)
                && MIME_TYPE.matcher(mimeType).matches();
    }

    private static List<String> immutableValidatedPath(List<String> pathSegments) {
        if (pathSegments == null || pathSegments.isEmpty()) {
            throw new IllegalArgumentException("Entry path must not be empty");
        }
        if (pathSegments.size() > MAX_DEPTH) {
            throw new IllegalArgumentException("Entry path is too deep");
        }
        ArrayList<String> copy = new ArrayList<String>(pathSegments.size());
        int encodedLength = 0;
        for (String segment : pathSegments) {
            validatePathSegment(segment);
            encodedLength += segment.getBytes(StandardCharsets.UTF_8).length;
            if (encodedLength > MAX_PATH_UTF8_BYTES) {
                throw new IllegalArgumentException("Entry path is too long");
            }
            copy.add(segment);
        }
        return Collections.unmodifiableList(copy);
    }

    private static long requireNonNegative(long value, String field) {
        if (value < 0L) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return value;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static void rejectControlCharacters(String value, String field) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            int type = Character.getType(codePoint);
            if (codePoint == 0
                    || codePoint == 0x7f
                    || codePoint < 0x20
                    || type == Character.SURROGATE) {
                throw new IllegalArgumentException(field + " contains an invalid character");
            }
            offset += Character.charCount(codePoint);
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ManifestEntry)) {
            return false;
        }
        ManifestEntry that = (ManifestEntry) other;
        return size == that.size
                && lastModified == that.lastModified
                && pathSegments.equals(that.pathSegments)
                && type == that.type
                && Objects.equals(objectId, that.objectId)
                && Objects.equals(mimeType, that.mimeType)
                && Objects.equals(sha256, that.sha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                pathSegments, type, objectId, size, lastModified, mimeType, sha256);
    }

    @Override
    public String toString() {
        return "ManifestEntry{" + type + " " + displayPath() + "}";
    }
}
