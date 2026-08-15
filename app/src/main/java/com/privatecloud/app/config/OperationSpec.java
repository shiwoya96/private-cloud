package com.privatecloud.app.config;

import com.privatecloud.app.model.SnapshotManifest;
import com.privatecloud.app.backup.RecoveryKeyCrypto;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Immutable complete inputs for one WorkRequest, serialized only inside an AES-GCM envelope. */
public final class OperationSpec {
    private static final int SCHEMA_VERSION = 5;
    private static final int MAX_TREE_URI_LENGTH = 2048;

    private final String operation;
    private final String snapshotId;
    private final StoredConfiguration configuration;
    private final String selectionPath;
    private final String exclusions;
    private final String recoveryKey;
    private final int retentionCount;

    public OperationSpec(
            String operation,
            String snapshotId,
            ConnectionSettings settings,
            String treeUri) {
        this(operation, snapshotId, settings, treeUri, "", "", "", 10);
    }

    public OperationSpec(
            String operation, String snapshotId, ConnectionSettings settings,
            String treeUri, String selectionPath) {
        this(operation, snapshotId, settings, treeUri, selectionPath, "", "", 10);
    }

    public OperationSpec(
            String operation, String snapshotId, ConnectionSettings settings,
            String treeUri, String selectionPath, String exclusions) {
        this(operation, snapshotId, settings, treeUri, selectionPath, exclusions, "", 10);
    }

    public OperationSpec(
            String operation, String snapshotId, ConnectionSettings settings,
            String treeUri, String selectionPath, String exclusions, String recoveryKey) {
        this(operation, snapshotId, settings, treeUri, selectionPath, exclusions, recoveryKey, 10);
    }

    public OperationSpec(
            String operation, String snapshotId, ConnectionSettings settings,
            String treeUri, String selectionPath, String exclusions, String recoveryKey,
            int retentionCount) {
        if (operation == null || operation.isEmpty()) {
            throw new IllegalArgumentException("Missing operation");
        }
        this.operation = operation;
        this.snapshotId = snapshotId == null ? "" : snapshotId;
        this.configuration = new StoredConfiguration(settings, treeUri);
        this.selectionPath = normalizeSelection(selectionPath);
        this.exclusions = new ExclusionRules(exclusions).encode();
        this.recoveryKey = recoveryKey == null ? "" : recoveryKey.trim();
        if (!this.recoveryKey.isEmpty()) RecoveryKeyCrypto.parseRecoveryKey(this.recoveryKey);
        if (retentionCount < 1 || retentionCount > 10_000) {
            throw new IllegalArgumentException("Invalid retention count");
        }
        this.retentionCount = retentionCount;
    }

    public String getOperation() {
        return operation;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public ConnectionSettings getSettings() {
        return configuration.getSettings();
    }

    public String getTreeUri() {
        return configuration.getTreeUri();
    }

    public String getSelectionPath() { return selectionPath; }
    public String getExclusions() { return exclusions; }
    public String getRecoveryKey() { return recoveryKey; }
    public int getRetentionCount() { return retentionCount; }

    String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("v", SCHEMA_VERSION);
            root.put("operation", operation);
            root.put("snapshotId", snapshotId);
            root.put("selectionPath", selectionPath);
            root.put("exclusions", exclusions);
            root.put("recoveryKey", recoveryKey);
            root.put("retentionCount", retentionCount);
            root.put("configuration", new JSONObject(configuration.toJson()));
            return root.toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException("Unable to encode operation", impossible);
        }
    }

    static OperationSpec fromJson(String json) {
        try {
            JSONObject root = parseObject(json);
            Object rawVersion = root.get("v");
            if (!(rawVersion instanceof Number)
                    || ((Number) rawVersion).intValue() < 1
                    || ((Number) rawVersion).intValue() > SCHEMA_VERSION
                    || ((Number) rawVersion).longValue() != ((Number) rawVersion).intValue()
                    || ((Number) rawVersion).doubleValue()
                            != (double) ((Number) rawVersion).intValue()) {
                throw new IllegalArgumentException("Unsupported operation schema");
            }
            String operation = requiredString(root, "operation");
            String snapshotId = requiredString(root, "snapshotId");
            String selectionPath = ((Number) rawVersion).intValue() >= 2
                    ? requiredString(root, "selectionPath") : "";
            String exclusions = ((Number) rawVersion).intValue() >= 3
                    ? requiredString(root, "exclusions") : "";
            String recoveryKey = ((Number) rawVersion).intValue() >= 4
                    ? requiredString(root, "recoveryKey") : "";
            int retentionCount = ((Number) rawVersion).intValue() >= 5
                    ? requiredInt(root, "retentionCount") : 10;
            Object rawConfiguration = root.get("configuration");
            if (!(rawConfiguration instanceof JSONObject)) {
                throw new JSONException("Expected configuration object");
            }
            StoredConfiguration configuration = StoredConfiguration.fromJson(
                    rawConfiguration.toString());
            return new OperationSpec(
                    operation,
                    snapshotId,
                    configuration.getSettings(),
                    configuration.getTreeUri(),
                    selectionPath,
                    exclusions,
                    recoveryKey,
                    retentionCount);
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("Malformed operation configuration", malformed);
        }
    }

    public static void validateSemantics(OperationSpec spec) {
        if (spec == null) throw new IllegalArgumentException("Missing operation specification");
        if (!"test".equals(spec.operation)
                && !"backup".equals(spec.operation)
                && !"list".equals(spec.operation)
                && !"restore".equals(spec.operation)
                && !"delete".equals(spec.operation)
                && !"prune".equals(spec.operation)
                && !"inspect".equals(spec.operation)) {
            throw new IllegalArgumentException("Unknown operation");
        }
        boolean snapshotOperation = "restore".equals(spec.operation)
                || "delete".equals(spec.operation) || "inspect".equals(spec.operation);
        boolean prune = "prune".equals(spec.operation);
        if ((snapshotOperation || prune) != !spec.snapshotId.isEmpty()) {
            throw new IllegalArgumentException("Invalid snapshot operation argument");
        }
        if (snapshotOperation) SnapshotManifest.requireSnapshotId(spec.snapshotId);
        if (prune) {
            try {
                int keep = Integer.parseInt(spec.snapshotId);
                if (keep < 1 || keep > 10_000) throw new NumberFormatException();
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("Invalid retention count", invalid);
            }
        }
        if (spec.configuration.getTreeUri().length() > MAX_TREE_URI_LENGTH) {
            throw new IllegalArgumentException("Selected directory URI is too long");
        }
        if (!"restore".equals(spec.operation) && !spec.selectionPath.isEmpty()) {
            throw new IllegalArgumentException("Selection is only supported for restore");
        }
    }

    private static String normalizeSelection(String value) {
        String path = value == null ? "" : value.trim();
        while (path.startsWith("/")) path = path.substring(1);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.isEmpty()) return "";
        if (path.length() > 2048 || path.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid restore selection");
        }
        for (String part : path.split("/", -1)) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new IllegalArgumentException("Invalid restore selection");
            }
        }
        return path;
    }

    private static String requiredString(JSONObject object, String name) throws JSONException {
        Object value = object.get(name);
        if (!(value instanceof String)) throw new JSONException("Expected string: " + name);
        return (String) value;
    }

    private static int requiredInt(JSONObject object, String name) throws JSONException {
        Object value = object.get(name);
        if (!(value instanceof Number)) throw new JSONException("Expected integer: " + name);
        Number number = (Number) value;
        int result = number.intValue();
        if (number.longValue() != result || number.doubleValue() != result) {
            throw new JSONException("Invalid integer: " + name);
        }
        return result;
    }

    private static JSONObject parseObject(String json) throws JSONException {
        JSONTokener tokens = new JSONTokener(json);
        Object value = tokens.nextValue();
        if (!(value instanceof JSONObject) || tokens.nextClean() != '\0') {
            throw new JSONException("Expected exactly one operation object");
        }
        return (JSONObject) value;
    }
}
