package com.privatecloud.app.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.privatecloud.app.model.SnapshotManifest;

import java.security.GeneralSecurityException;

/** Persists connection fields and SAF selection in the application's private storage. */
public final class SettingsRepository {
    private static final String PREFS = "private_cloud_settings_v1";
    private static final String PASSWORD_KEY = "active_connection_password";

    private final SharedPreferences values;
    private final CredentialVault vault;

    public SettingsRepository(Context context) {
        Context app = context.getApplicationContext();
        values = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        vault = new CredentialVault(app);
    }

    public synchronized void save(ConnectionSettings settings, Uri treeUri)
            throws GeneralSecurityException {
        SharedPreferences.Editor editor = values.edit()
                .putString("protocol", settings.getProtocol().name())
                .putString("webdav_url", settings.getWebDavUrl())
                .putString("smb_host", settings.getSmbHost())
                .putInt("smb_port", settings.getSmbPort())
                .putString("smb_share", settings.getSmbShare())
                .putString("smb_domain", settings.getSmbDomain())
                .putString("username", settings.getUsername())
                .putString("remote_path", settings.getRemotePath())
                .putString("plan_id", settings.getPlanId());
        if (treeUri != null) editor.putString("tree_uri", treeUri.toString());
        if (!editor.commit()) throw new GeneralSecurityException("Unable to save settings");
        vault.put(PASSWORD_KEY, settings.getPassword());
    }

    public synchronized ConnectionSettings load() throws GeneralSecurityException {
        String rawProtocol = values.getString("protocol", ConnectionSettings.Protocol.WEBDAV.name());
        ConnectionSettings.Protocol protocol;
        try {
            protocol = ConnectionSettings.Protocol.valueOf(rawProtocol);
        } catch (IllegalArgumentException ignored) {
            protocol = ConnectionSettings.Protocol.WEBDAV;
        }
        String planId = stablePlanId();
        return new ConnectionSettings(
                protocol,
                values.getString("webdav_url", ""),
                values.getString("smb_host", ""),
                values.getInt("smb_port", 445),
                values.getString("smb_share", ""),
                values.getString("smb_domain", ""),
                values.getString("username", ""),
                vault.get(PASSWORD_KEY),
                values.getString("remote_path", ""),
                planId);
    }

    public synchronized Uri loadTreeUri() {
        String value = values.getString("tree_uri", "");
        return value == null || value.isEmpty() ? null : Uri.parse(value);
    }

    public synchronized void saveTreeUri(Uri treeUri) throws GeneralSecurityException {
        if (treeUri == null || !values.edit().putString("tree_uri", treeUri.toString()).commit()) {
            throw new GeneralSecurityException("Unable to save selected directory");
        }
    }

    public synchronized String getOrCreatePlanId() {
        return stablePlanId();
    }

    /**
     * The current UI exposes one active plan, so its remote namespace must survive reinstall and
     * device replacement. Migrate early development builds that stored a per-install UUID.
     */
    private String stablePlanId() {
        String value = values.getString("plan_id", "");
        if (!SnapshotManifest.DEFAULT_PLAN_ID.equals(value)) {
            values.edit().putString("plan_id", SnapshotManifest.DEFAULT_PLAN_ID).commit();
        }
        return SnapshotManifest.DEFAULT_PLAN_ID;
    }
}
