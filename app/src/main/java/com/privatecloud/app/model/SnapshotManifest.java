package com.privatecloud.app.model;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Versioned, validated description of one complete local directory snapshot. */
public final class SnapshotManifest {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_ENTRIES = 100_000;
    public static final int MAX_JSON_BYTES = 16 * 1024 * 1024;
    /** Stable remote namespace for the MVP's single active backup plan. */
    public static final String DEFAULT_PLAN_ID = "default-plan";

    private static final Pattern PLAN_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern SNAPSHOT_ID = Pattern.compile("[A-Za-z0-9_.-]{1,96}");

    private final String planId;
    private final String snapshotId;
    private final long createdAt;
    private final String sourceName;
    private final List<ManifestEntry> entries;
    private final long fileCount;
    private final long directoryCount;
    private final long totalBytes;

    public SnapshotManifest(
            String planId,
            String snapshotId,
            long createdAt,
            String sourceName,
            List<ManifestEntry> entries) {
        this.planId = requirePlanId(planId);
        this.snapshotId = requireSnapshotId(snapshotId);
        if (createdAt <= 0L) {
            throw new IllegalArgumentException("createdAt must be positive");
        }
        this.createdAt = createdAt;
        this.sourceName = requireSourceName(sourceName);
        if (entries == null) {
            throw new IllegalArgumentException("entries must not be null");
        }
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("Snapshot contains too many entries");
        }

        ArrayList<ManifestEntry> sorted = new ArrayList<ManifestEntry>(entries);
        for (ManifestEntry entry : sorted) {
            Objects.requireNonNull(entry, "entry");
        }
        Collections.sort(sorted, ENTRY_ORDER);
        validateHierarchy(sorted);
        this.entries = Collections.unmodifiableList(sorted);

        long files = 0L;
        long directories = 0L;
        long bytes = 0L;
        for (ManifestEntry entry : sorted) {
            if (entry.isDirectory()) {
                directories++;
            } else {
                files++;
                bytes = checkedAdd(bytes, entry.getSize(), "Snapshot byte count overflow");
            }
        }
        this.fileCount = files;
        this.directoryCount = directories;
        this.totalBytes = bytes;
    }

    public String getPlanId() {
        return planId;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public String getSourceName() {
        return sourceName;
    }

    public List<ManifestEntry> getEntries() {
        return entries;
    }

    public long getFileCount() {
        return fileCount;
    }

    public long getDirectoryCount() {
        return directoryCount;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    /** Serializes using a stable field and entry order. The returned bytes are the hashed payload. */
    public byte[] toJsonBytes() throws IOException {
        try {
            JSONObject root = new JSONObject();
            root.put("schema", SCHEMA_VERSION);
            root.put("planId", planId);
            root.put("snapshotId", snapshotId);
            root.put("createdAt", createdAt);
            root.put("sourceName", sourceName);
            JSONArray encodedEntries = new JSONArray();
            for (ManifestEntry entry : entries) {
                JSONObject encoded = new JSONObject();
                JSONArray path = new JSONArray();
                for (String segment : entry.getPathSegments()) {
                    path.put(segment);
                }
                encoded.put("path", path);
                encoded.put("type", entry.isDirectory() ? "directory" : "file");
                encoded.put("lastModified", entry.getLastModified());
                if (!entry.isDirectory()) {
                    encoded.put("objectId", entry.getObjectId());
                    encoded.put("size", entry.getSize());
                    if (entry.getMimeType() != null) {
                        encoded.put("mimeType", entry.getMimeType());
                    }
                    encoded.put("sha256", entry.getSha256());
                }
                encodedEntries.put(encoded);
            }
            root.put("entries", encodedEntries);
            byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_JSON_BYTES) {
                throw new IOException("Snapshot manifest exceeds " + MAX_JSON_BYTES + " bytes");
            }
            return bytes;
        } catch (JSONException impossible) {
            throw new IOException("Unable to serialize snapshot manifest", impossible);
        }
    }

    public static SnapshotManifest fromJsonBytes(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("Snapshot manifest is empty");
        }
        if (bytes.length > MAX_JSON_BYTES) {
            throw new IOException("Snapshot manifest exceeds " + MAX_JSON_BYTES + " bytes");
        }
        try {
            JSONObject root = decodeObject(bytes);
            long schema = requireLong(root, "schema");
            if (schema != SCHEMA_VERSION) {
                throw new IOException("Unsupported snapshot manifest schema: " + schema);
            }
            String planId = requireString(root, "planId");
            String snapshotId = requireString(root, "snapshotId");
            long createdAt = requireLong(root, "createdAt");
            String sourceName = requireString(root, "sourceName");
            JSONArray encodedEntries = root.getJSONArray("entries");
            if (encodedEntries.length() > MAX_ENTRIES) {
                throw new IOException("Snapshot contains too many entries");
            }

            ArrayList<ManifestEntry> entries =
                    new ArrayList<ManifestEntry>(encodedEntries.length());
            for (int index = 0; index < encodedEntries.length(); index++) {
                JSONObject encoded = encodedEntries.getJSONObject(index);
                JSONArray encodedPath = encoded.getJSONArray("path");
                if (encodedPath.length() == 0
                        || encodedPath.length() > ManifestEntry.MAX_DEPTH) {
                    throw new IOException("Invalid entry path depth at index " + index);
                }
                ArrayList<String> path = new ArrayList<String>(encodedPath.length());
                for (int pathIndex = 0; pathIndex < encodedPath.length(); pathIndex++) {
                    Object segment = encodedPath.get(pathIndex);
                    if (!(segment instanceof String)) {
                        throw new IOException("Entry path segment is not a string");
                    }
                    path.add((String) segment);
                }

                String type = requireString(encoded, "type");
                long lastModified = requireLong(encoded, "lastModified");
                if ("directory".equals(type)) {
                    if (encoded.has("objectId")
                            || encoded.has("size")
                            || encoded.has("mimeType")
                            || encoded.has("sha256")) {
                        throw new IOException(
                                "Directory entry contains forbidden file metadata");
                    }
                    entries.add(ManifestEntry.directory(path, lastModified));
                } else if ("file".equals(type)) {
                    String mimeType = null;
                    if (encoded.has("mimeType") && !encoded.isNull("mimeType")) {
                        mimeType = requireString(encoded, "mimeType");
                    }
                    entries.add(ManifestEntry.file(
                            path,
                            requireString(encoded, "objectId").toLowerCase(Locale.US),
                            requireLong(encoded, "size"),
                            lastModified,
                            mimeType,
                            requireString(encoded, "sha256").toLowerCase(Locale.US)));
                } else {
                    throw new IOException("Unknown snapshot entry type: " + type);
                }
            }
            return new SnapshotManifest(planId, snapshotId, createdAt, sourceName, entries);
        } catch (JSONException malformed) {
            throw new IOException("Invalid snapshot manifest JSON", malformed);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid snapshot manifest", invalid);
        }
    }

    public static String requirePlanId(String value) {
        if (value == null || !PLAN_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "planId must contain 1-64 ASCII letters, digits, '_' or '-'");
        }
        return value;
    }

    public static String requireSnapshotId(String value) {
        if (value == null
                || !SNAPSHOT_ID.matcher(value).matches()
                || value.equals(".")
                || value.equals("..")) {
            throw new IllegalArgumentException("Invalid snapshotId");
        }
        return value;
    }

    private static void validateHierarchy(List<ManifestEntry> entries) {
        Map<String, ManifestEntry> byPath = new HashMap<String, ManifestEntry>();
        Set<String> objectIds = new HashSet<String>();
        for (ManifestEntry entry : entries) {
            String key = entry.pathKey();
            if (byPath.put(key, entry) != null) {
                throw new IllegalArgumentException("Duplicate snapshot path: " + key);
            }
            if (!entry.isDirectory() && !objectIds.add(entry.getObjectId())) {
                throw new IllegalArgumentException(
                        "Snapshot object is referenced more than once: " + entry.getObjectId());
            }
        }
        for (ManifestEntry entry : entries) {
            List<String> path = entry.getPathSegments();
            if (path.size() <= 1) {
                continue;
            }
            for (int index = 0; index < path.size() - 1; index++) {
                String parentKey = ManifestEntry.pathKey(path, index + 1);
                ManifestEntry parentEntry = byPath.get(parentKey);
                if (parentEntry == null || !parentEntry.isDirectory()) {
                    throw new IllegalArgumentException(
                            "Missing directory parent for " + entry.displayPath());
                }
            }
        }
    }

    public static String requireSourceName(String value) {
        if (value == null || value.isEmpty()) {
            return "Selected folder";
        }
        if (value.length() > 512
                || value.getBytes(StandardCharsets.UTF_8).length > 2048) {
            throw new IllegalArgumentException("sourceName is too long");
        }
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (codePoint == 0 || codePoint == 0x7f || codePoint < 0x20
                    || Character.getType(codePoint) == Character.SURROGATE) {
                throw new IllegalArgumentException("sourceName contains an invalid character");
            }
            offset += Character.charCount(codePoint);
        }
        return value;
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

    private static long checkedAdd(long left, long right, String message) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            throw new IllegalArgumentException(message);
        }
        return left + right;
    }

    private static final Comparator<ManifestEntry> ENTRY_ORDER =
            new Comparator<ManifestEntry>() {
                @Override
                public int compare(ManifestEntry left, ManifestEntry right) {
                    List<String> leftPath = left.getPathSegments();
                    List<String> rightPath = right.getPathSegments();
                    int common = Math.min(leftPath.size(), rightPath.size());
                    for (int index = 0; index < common; index++) {
                        int compared = leftPath.get(index).compareTo(rightPath.get(index));
                        if (compared != 0) {
                            return compared;
                        }
                    }
                    if (leftPath.size() != rightPath.size()) {
                        return leftPath.size() < rightPath.size() ? -1 : 1;
                    }
                    return left.getType().compareTo(right.getType());
                }
            };
}
