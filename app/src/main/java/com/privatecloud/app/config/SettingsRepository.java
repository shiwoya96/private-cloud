package com.privatecloud.app.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.privatecloud.app.model.SnapshotManifest;

import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persists one encrypted active configuration and creates encrypted WorkRequest snapshots. */
public final class SettingsRepository {
    private static final Object STORAGE_LOCK = new Object();

    private static final String PREFS = "private_cloud_settings_v1";
    private static final String ACTIVE_ENVELOPE = "active_configuration_envelope_v2";
    private static final String ACTIVE_AAD = "active-configuration|schema=2";
    private static final String PLAN_PREFIX = "saved_plan.";
    private static final String RECOVERY_PREFIX = "recovery_key.";
    private static final String LEGACY_PASSWORD_NAME = "active_connection_password";
    private static final String LEGACY_PASSWORD_CIPHERTEXT = "password_ciphertext";
    private static final String LEGACY_JOB_PREFIX = "job.";

    private static final String[] LEGACY_ACTIVE_KEYS = {
            "protocol",
            "webdav_url",
            "smb_host",
            "smb_port",
            "smb_share",
            "smb_domain",
            "username",
            LEGACY_PASSWORD_CIPHERTEXT,
            "remote_path",
            "plan_id",
            "tree_uri",
            "active_job_key"
    };

    private final SharedPreferences values;
    private final CredentialVault vault;

    public SettingsRepository(Context context) {
        Context app = context.getApplicationContext();
        values = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        vault = new CredentialVault(app);
    }

    /** Saves settings, password and SAF URI as one authenticated encrypted value. */
    public void save(ConnectionSettings settings, Uri treeUri)
            throws GeneralSecurityException {
        synchronized (STORAGE_LOCK) {
            persistActive(new StoredConfiguration(settings, uriString(treeUri)));
        }
    }

    /**
     * Atomically saves the active configuration and returns an immutable encrypted operation
     * envelope bound to the WorkRequest UUID. No secret or SAF URI is placed in Data as plaintext.
     */
    public String prepareOperation(
            String rawJobKey,
            ConnectionSettings settings,
            Uri treeUri,
            String operation,
            String snapshotId) throws GeneralSecurityException {
        return prepareOperation(rawJobKey, settings, treeUri, operation, snapshotId, "");
    }

    public String prepareOperation(
            String rawJobKey, ConnectionSettings settings, Uri treeUri,
            String operation, String snapshotId, String selectionPath)
            throws GeneralSecurityException {
        return prepareOperation(
                rawJobKey, settings, treeUri, operation, snapshotId, selectionPath, "");
    }

    public String prepareOperation(
            String rawJobKey, ConnectionSettings settings, Uri treeUri,
            String operation, String snapshotId, String selectionPath, String exclusions)
            throws GeneralSecurityException {
        return prepareOperation(
                rawJobKey, settings, treeUri, operation, snapshotId, selectionPath,
                exclusions, "");
    }

    public String prepareOperation(
            String rawJobKey, ConnectionSettings settings, Uri treeUri,
            String operation, String snapshotId, String selectionPath, String exclusions,
            String recoveryKey) throws GeneralSecurityException {
        return prepareOperation(rawJobKey, settings, treeUri, operation, snapshotId,
                selectionPath, exclusions, recoveryKey, 10);
    }

    public String prepareOperation(
            String rawJobKey, ConnectionSettings settings, Uri treeUri,
            String operation, String snapshotId, String selectionPath, String exclusions,
            String recoveryKey, int retentionCount) throws GeneralSecurityException {
        synchronized (STORAGE_LOCK) {
            String jobKey = JobKey.requireCanonical(rawJobKey);
            StoredConfiguration configuration = new StoredConfiguration(
                    settings, uriString(treeUri));
            String activeEnvelope = vault.encrypt(ACTIVE_AAD, configuration.toJson());
            String planEnvelope = vault.encrypt(
                    planAad(settings.getPlanId()), configuration.toJson());
            OperationSpec spec;
            try {
                spec = new OperationSpec(
                        operation, snapshotId, settings, configuration.getTreeUri(),
                        selectionPath, exclusions, recoveryKey, retentionCount);
                OperationSpec.validateSemantics(spec);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Invalid background task configuration", invalid);
            }
            String workEnvelope = vault.encrypt(WorkEnvelopePolicy.aad(jobKey), spec.toJson());
            WorkEnvelopePolicy.requireFits(workEnvelope);

            SharedPreferences.Editor editor = values.edit()
                    .putString(ACTIVE_ENVELOPE, activeEnvelope)
                    .putString(PLAN_PREFIX + settings.getPlanId(), planEnvelope);
            removeLegacyValues(editor, values.getAll());
            commitOrThrow(editor, "Unable to save active configuration");
            vault.removeLegacyCiphertext(LEGACY_PASSWORD_NAME);
            return workEnvelope;
        }
    }

    /** Decrypts only the operation envelope cryptographically bound to this WorkRequest UUID. */
    public OperationSpec decryptOperation(String rawJobKey, String envelope)
            throws GeneralSecurityException {
        synchronized (STORAGE_LOCK) {
            String jobKey = JobKey.requireCanonical(rawJobKey);
            if (envelope == null || envelope.isEmpty()) {
                throw new GeneralSecurityException("Background task configuration is missing");
            }
            try {
                WorkEnvelopePolicy.requireFits(envelope);
                OperationSpec spec = OperationSpec.fromJson(
                        vault.decrypt(WorkEnvelopePolicy.aad(jobKey), envelope));
                OperationSpec.validateSemantics(spec);
                return spec;
            } catch (IllegalArgumentException damaged) {
                throw new GeneralSecurityException(
                        "Background task configuration is damaged", damaged);
            }
        }
    }

    public ConnectionSettings load() throws GeneralSecurityException {
        synchronized (STORAGE_LOCK) {
            return loadStoredConfiguration().getSettings();
        }
    }

    public Uri loadTreeUri() throws GeneralSecurityException {
        synchronized (STORAGE_LOCK) {
            String raw = loadStoredConfiguration().getTreeUri();
            return raw.isEmpty() ? null : Uri.parse(raw);
        }
    }

    public void saveTreeUri(Uri treeUri) throws GeneralSecurityException {
        if (treeUri == null) throw new GeneralSecurityException("Missing selected directory");
        synchronized (STORAGE_LOCK) {
            StoredConfiguration current = loadStoredConfiguration();
            persistActive(new StoredConfiguration(current.getSettings(), treeUri.toString()));
        }
    }

    public String getOrCreatePlanId() {
        try {
            return load().getPlanId();
        } catch (GeneralSecurityException | IllegalArgumentException ignored) {
            return SnapshotManifest.DEFAULT_PLAN_ID;
        }
    }

    public List<String> listPlanIds() {
        ArrayList<String> ids = new ArrayList<String>();
        for (String key : values.getAll().keySet()) {
            if (key.startsWith(PLAN_PREFIX)) ids.add(key.substring(PLAN_PREFIX.length()));
        }
        Collections.sort(ids);
        return Collections.unmodifiableList(ids);
    }

    public ConnectionSettings loadPlan(String planId) throws GeneralSecurityException {
        synchronized (STORAGE_LOCK) { return loadPlanConfiguration(planId).getSettings(); }
    }

    public Uri loadPlanTreeUri(String planId) throws GeneralSecurityException {
        synchronized (STORAGE_LOCK) {
            String raw = loadPlanConfiguration(planId).getTreeUri();
            return raw.isEmpty() ? null : Uri.parse(raw);
        }
    }

    public void deletePlan(String planId) throws GeneralSecurityException {
        String safeId = SnapshotManifest.requirePlanId(planId);
        SharedPreferences.Editor editor = values.edit().remove(PLAN_PREFIX + safeId)
                .remove(RECOVERY_PREFIX + safeId);
        if (loadStoredConfiguration().getSettings().getPlanId().equals(safeId)) {
            editor.remove(ACTIVE_ENVELOPE);
        }
        if (!editor.commit()) {
            throw new GeneralSecurityException("Unable to delete plan");
        }
    }

    public void saveRecoveryKey(String planId, String recoveryKey)
            throws GeneralSecurityException {
        String safeId = SnapshotManifest.requirePlanId(planId);
        String key = recoveryKey == null ? "" : recoveryKey.trim();
        if (!key.isEmpty()) com.privatecloud.app.backup.RecoveryKeyCrypto.parseRecoveryKey(key);
        String encrypted = vault.encrypt(planAad(safeId) + "|recovery", key);
        commitOrThrow(values.edit().putString(RECOVERY_PREFIX + safeId, encrypted),
                "Unable to save recovery key");
    }

    public String loadRecoveryKey(String planId) throws GeneralSecurityException {
        String safeId = SnapshotManifest.requirePlanId(planId);
        String encrypted = values.getString(RECOVERY_PREFIX + safeId, null);
        return encrypted == null ? "" : vault.decrypt(planAad(safeId) + "|recovery", encrypted);
    }

    public void clearAll() throws GeneralSecurityException {
        if (!values.edit().clear().commit()) {
            throw new GeneralSecurityException("Unable to clear settings");
        }
        vault.removeLegacyCiphertext(LEGACY_PASSWORD_NAME);
    }

    private StoredConfiguration loadPlanConfiguration(String planId)
            throws GeneralSecurityException {
        String safeId = SnapshotManifest.requirePlanId(planId);
        String envelope = values.getString(PLAN_PREFIX + safeId, null);
        if (envelope == null) throw new GeneralSecurityException("Backup plan does not exist");
        try {
            return StoredConfiguration.fromJson(vault.decrypt(planAad(safeId), envelope));
        } catch (IllegalArgumentException damaged) {
            throw new GeneralSecurityException("Saved backup plan is damaged", damaged);
        }
    }

    private StoredConfiguration loadStoredConfiguration() throws GeneralSecurityException {
        Map<String, ?> stored = values.getAll();
        String envelope = optionalString(stored, ACTIVE_ENVELOPE, null);
        if (envelope != null) {
            try {
                return StoredConfiguration.fromJson(vault.decrypt(ACTIVE_AAD, envelope));
            } catch (IllegalArgumentException damaged) {
                throw new GeneralSecurityException("Stored configuration is damaged", damaged);
            }
        }

        StoredConfiguration migrated = readLegacy(stored);
        if (migrated == null) return emptyConfiguration();
        persistActive(migrated);
        return migrated;
    }

    private StoredConfiguration readLegacy(Map<String, ?> stored)
            throws GeneralSecurityException {
        boolean hasLegacyPublicFields = stored.containsKey("protocol")
                || stored.containsKey("webdav_url")
                || stored.containsKey("smb_host")
                || stored.containsKey("tree_uri");
        String ciphertext = optionalString(stored, LEGACY_PASSWORD_CIPHERTEXT, null);
        if (ciphertext == null) ciphertext = vault.getLegacyCiphertext(LEGACY_PASSWORD_NAME);
        if (!hasLegacyPublicFields && ciphertext == null) return null;

        String password = ciphertext == null
                ? ""
                : vault.decrypt(LEGACY_PASSWORD_NAME, ciphertext);
        ConnectionSettings.Protocol protocol = legacyProtocol(stored);
        ConnectionSettings settings = new ConnectionSettings(
                protocol,
                optionalString(stored, "webdav_url", ""),
                optionalString(stored, "smb_host", ""),
                optionalInt(stored, "smb_port", 445),
                optionalString(stored, "smb_share", ""),
                optionalString(stored, "smb_domain", ""),
                optionalString(stored, "username", ""),
                password,
                optionalString(stored, "remote_path", ""),
                SnapshotManifest.DEFAULT_PLAN_ID);
        return new StoredConfiguration(settings, optionalString(stored, "tree_uri", ""));
    }

    private void persistActive(StoredConfiguration configuration)
            throws GeneralSecurityException {
        String envelope = vault.encrypt(ACTIVE_AAD, configuration.toJson());
        String planId = configuration.getSettings().getPlanId();
        String planEnvelope = vault.encrypt(planAad(planId), configuration.toJson());
        SharedPreferences.Editor editor = values.edit()
                .putString(ACTIVE_ENVELOPE, envelope)
                .putString(PLAN_PREFIX + planId, planEnvelope);
        removeLegacyValues(editor, values.getAll());
        commitOrThrow(editor, "Unable to save settings");
        vault.removeLegacyCiphertext(LEGACY_PASSWORD_NAME);
    }

    private static String planAad(String planId) {
        return "saved-plan|schema=1|id=" + SnapshotManifest.requirePlanId(planId);
    }

    private static StoredConfiguration emptyConfiguration() {
        ConnectionSettings settings = new ConnectionSettings(
                ConnectionSettings.Protocol.WEBDAV,
                "",
                "",
                445,
                "",
                "",
                "",
                "",
                "",
                SnapshotManifest.DEFAULT_PLAN_ID);
        return new StoredConfiguration(settings, "");
    }

    private static ConnectionSettings.Protocol legacyProtocol(Map<String, ?> stored) {
        String raw = optionalString(
                stored, "protocol", ConnectionSettings.Protocol.WEBDAV.name());
        try {
            return ConnectionSettings.Protocol.valueOf(raw);
        } catch (IllegalArgumentException ignored) {
            return ConnectionSettings.Protocol.WEBDAV;
        }
    }

    private static void removeLegacyValues(
            SharedPreferences.Editor editor, Map<String, ?> stored) {
        for (String key : LEGACY_ACTIVE_KEYS) editor.remove(key);
        for (String key : stored.keySet()) {
            if (key.startsWith(LEGACY_JOB_PREFIX)) editor.remove(key);
        }
    }

    private static String uriString(Uri uri) {
        return uri == null ? "" : uri.toString();
    }

    private static String optionalString(Map<String, ?> stored, String key, String fallback) {
        Object value = stored.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    private static int optionalInt(Map<String, ?> stored, String key, int fallback) {
        Object value = stored.get(key);
        return value instanceof Integer ? (Integer) value : fallback;
    }

    private static void commitOrThrow(SharedPreferences.Editor editor, String message)
            throws GeneralSecurityException {
        if (!editor.commit()) throw new GeneralSecurityException(message);
    }
}
