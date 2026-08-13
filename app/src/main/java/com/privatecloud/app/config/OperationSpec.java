package com.privatecloud.app.config;

import com.privatecloud.app.model.SnapshotManifest;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Immutable complete inputs for one WorkRequest, serialized only inside an AES-GCM envelope. */
public final class OperationSpec {
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_TREE_URI_LENGTH = 2048;

    private final String operation;
    private final String snapshotId;
    private final StoredConfiguration configuration;

    public OperationSpec(
            String operation,
            String snapshotId,
            ConnectionSettings settings,
            String treeUri) {
        if (operation == null || operation.isEmpty()) {
            throw new IllegalArgumentException("Missing operation");
        }
        this.operation = operation;
        this.snapshotId = snapshotId == null ? "" : snapshotId;
        this.configuration = new StoredConfiguration(settings, treeUri);
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

    String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("v", SCHEMA_VERSION);
            root.put("operation", operation);
            root.put("snapshotId", snapshotId);
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
                    || ((Number) rawVersion).intValue() != SCHEMA_VERSION
                    || ((Number) rawVersion).longValue() != SCHEMA_VERSION
                    || ((Number) rawVersion).doubleValue() != (double) SCHEMA_VERSION) {
                throw new IllegalArgumentException("Unsupported operation schema");
            }
            String operation = requiredString(root, "operation");
            String snapshotId = requiredString(root, "snapshotId");
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
                    configuration.getTreeUri());
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("Malformed operation configuration", malformed);
        }
    }

    public static void validateSemantics(OperationSpec spec) {
        if (spec == null) throw new IllegalArgumentException("Missing operation specification");
        if (!"test".equals(spec.operation)
                && !"backup".equals(spec.operation)
                && !"list".equals(spec.operation)
                && !"restore".equals(spec.operation)) {
            throw new IllegalArgumentException("Unknown operation");
        }
        boolean restore = "restore".equals(spec.operation);
        if (restore != !spec.snapshotId.isEmpty()) {
            throw new IllegalArgumentException("Invalid snapshot selection");
        }
        if (restore) SnapshotManifest.requireSnapshotId(spec.snapshotId);
        if (spec.configuration.getTreeUri().length() > MAX_TREE_URI_LENGTH) {
            throw new IllegalArgumentException("Selected directory URI is too long");
        }
    }

    private static String requiredString(JSONObject object, String name) throws JSONException {
        Object value = object.get(name);
        if (!(value instanceof String)) throw new JSONException("Expected string: " + name);
        return (String) value;
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
