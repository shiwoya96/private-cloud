package com.privatecloud.app.config;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

/** Pure JSON representation used inside authenticated encrypted envelopes. */
final class StoredConfiguration {
    private static final int SCHEMA_VERSION = 1;

    private final ConnectionSettings settings;
    private final String treeUri;

    StoredConfiguration(ConnectionSettings settings, String treeUri) {
        if (settings == null) throw new IllegalArgumentException("Missing connection settings");
        this.settings = settings;
        this.treeUri = treeUri == null ? "" : treeUri;
    }

    ConnectionSettings getSettings() {
        return settings;
    }

    String getTreeUri() {
        return treeUri;
    }

    String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("v", SCHEMA_VERSION);
            root.put("protocol", settings.getProtocol().name());
            root.put("webdavUrl", settings.getWebDavUrl());
            root.put("smbHost", settings.getSmbHost());
            root.put("smbPort", settings.getSmbPort());
            root.put("smbShare", settings.getSmbShare());
            root.put("smbDomain", settings.getSmbDomain());
            root.put("username", settings.getUsername());
            root.put("password", settings.getPassword());
            root.put("remotePath", settings.getRemotePath());
            root.put("planId", settings.getPlanId());
            root.put("treeUri", treeUri);
            return root.toString();
        } catch (JSONException impossible) {
            throw new IllegalStateException("Unable to encode configuration", impossible);
        }
    }

    static StoredConfiguration fromJson(String json) {
        try {
            JSONObject root = parseObject(json);
            if (requiredInt(root, "v") != SCHEMA_VERSION) {
                throw new IllegalArgumentException("Unsupported configuration schema");
            }
            final ConnectionSettings.Protocol protocol;
            try {
                protocol = ConnectionSettings.Protocol.valueOf(requiredString(root, "protocol"));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Invalid stored protocol", invalid);
            }
            ConnectionSettings settings = new ConnectionSettings(
                    protocol,
                    requiredString(root, "webdavUrl"),
                    requiredString(root, "smbHost"),
                    requiredInt(root, "smbPort"),
                    requiredString(root, "smbShare"),
                    requiredString(root, "smbDomain"),
                    requiredString(root, "username"),
                    requiredString(root, "password"),
                    requiredString(root, "remotePath"),
                    requiredString(root, "planId"));
            return new StoredConfiguration(settings, requiredString(root, "treeUri"));
        } catch (JSONException malformed) {
            throw new IllegalArgumentException("Malformed stored configuration", malformed);
        }
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
        if (number.longValue() != result || number.doubleValue() != (double) result) {
            throw new JSONException("Invalid integer: " + name);
        }
        return result;
    }

    private static JSONObject parseObject(String json) throws JSONException {
        JSONTokener tokens = new JSONTokener(json);
        Object value = tokens.nextValue();
        if (!(value instanceof JSONObject) || tokens.nextClean() != '\0') {
            throw new JSONException("Expected exactly one configuration object");
        }
        return (JSONObject) value;
    }
}
