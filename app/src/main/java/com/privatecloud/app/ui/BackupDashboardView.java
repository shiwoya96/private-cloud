package com.privatecloud.app.ui;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.privatecloud.app.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Complete single-screen UI for configuring a private-cloud target and starting backup/restore
 * operations. This class deliberately depends only on Android framework widgets so that a host
 * Activity can use it with {@code setContentView(new BackupDashboardView(this))}.
 *
 * <p>The host owns networking, Storage Access Framework permission persistence, background work,
 * and credential storage. All public rendering methods must be called on the main thread.</p>
 */
public final class BackupDashboardView extends ScrollView {

    public enum Protocol {
        WEBDAV,
        SMB
    }

    /** Immutable values collected from the visible server form. */
    public static final class ServerConfig {
        private final Protocol protocol;
        private final String webDavUrl;
        private final String smbHost;
        private final int smbPort;
        private final String smbShare;
        private final String smbDomain;
        private final String username;
        private final String password;
        private final String remotePath;

        private ServerConfig(
                Protocol protocol,
                String webDavUrl,
                String smbHost,
                int smbPort,
                String smbShare,
                String smbDomain,
                String username,
                String password,
                String remotePath) {
            this.protocol = protocol;
            this.webDavUrl = valueOrEmpty(webDavUrl);
            this.smbHost = valueOrEmpty(smbHost);
            this.smbPort = smbPort;
            this.smbShare = valueOrEmpty(smbShare);
            this.smbDomain = valueOrEmpty(smbDomain);
            this.username = valueOrEmpty(username);
            this.password = valueOrEmpty(password);
            this.remotePath = valueOrEmpty(remotePath);
        }

        public static ServerConfig webDav(
                String url, String username, String password, String remotePath) {
            return new ServerConfig(
                    Protocol.WEBDAV, url, "", 445, "", "", username, password, remotePath);
        }

        public static ServerConfig smb(
                String host,
                int port,
                String share,
                String domain,
                String username,
                String password,
                String remotePath) {
            return new ServerConfig(
                    Protocol.SMB,
                    "",
                    host,
                    port,
                    share,
                    domain,
                    username,
                    password,
                    remotePath);
        }

        public Protocol getProtocol() {
            return protocol;
        }

        public String getWebDavUrl() {
            return webDavUrl;
        }

        public String getSmbHost() {
            return smbHost;
        }

        public int getSmbPort() {
            return smbPort;
        }

        public String getSmbShare() {
            return smbShare;
        }

        public String getSmbDomain() {
            return smbDomain;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }

        public String getRemotePath() {
            return remotePath;
        }

        /** WebDAV is encrypted only when an HTTPS URL is configured. SMB encryption is negotiated. */
        public boolean isWebDavTransportEncrypted() {
            return protocol != Protocol.WEBDAV
                    || webDavUrl.regionMatches(true, 0, "https://", 0, "https://".length());
        }
    }

    /** A remote backup exposed as a selectable restore source. */
    public static final class RemoteSnapshot {
        private final String id;
        private final String title;
        private final String details;

        public RemoteSnapshot(String id, String title, String details) {
            this.id = valueOrEmpty(id);
            this.title = valueOrEmpty(title);
            this.details = valueOrEmpty(details);
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public String getDetails() {
            return details;
        }
    }

    /** Callbacks implemented by the hosting Activity or its controller. */
    public interface Listener {
        void onChooseLocalFolder();

        void onTestConnection(ServerConfig config);

        void onStartBackup(ServerConfig config, Uri localFolder);

        void onLoadRemoteSnapshots(ServerConfig config);

        void onRestoreSnapshot(
                ServerConfig config, Uri localFolder, RemoteSnapshot snapshot);

        void onCancelOperation();
    }

    private RadioGroup protocolGroup;
    private RadioButton webDavRadio;
    private RadioButton smbRadio;
    private View webDavFields;
    private View smbFields;
    private EditText webDavUrl;
    private EditText smbHost;
    private EditText smbPort;
    private EditText smbShare;
    private EditText smbDomain;
    private EditText username;
    private EditText password;
    private EditText remotePath;
    private TextView selectedFolderText;
    private Button chooseFolderButton;
    private Button testConnectionButton;
    private Button backupButton;
    private Button loadSnapshotsButton;
    private RadioGroup snapshotGroup;
    private TextView snapshotEmptyText;
    private Button restoreButton;
    private TextView operationStatus;
    private ProgressBar operationProgress;
    private TextView operationDetail;
    private Button cancelButton;

    private Listener listener;
    private Uri selectedLocalFolder;
    private RemoteSnapshot selectedSnapshot;
    private List<RemoteSnapshot> snapshots = Collections.emptyList();
    private boolean operationRunning;

    public BackupDashboardView(Context context) {
        this(context, null);
    }

    public BackupDashboardView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BackupDashboardView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setFillViewport(true);
        inflate(context, R.layout.pc_view_backup_dashboard, this);
        bindViews();
        bindActions();
        renderProtocol(Protocol.WEBDAV);
        renderSnapshots();
        renderIdle();
        refreshActionAvailability();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public Protocol getSelectedProtocol() {
        return smbRadio.isChecked() ? Protocol.SMB : Protocol.WEBDAV;
    }

    /** Returns form values without validation. Prefer callback values for user-triggered actions. */
    public ServerConfig getServerConfig() {
        return collectConfig();
    }

    /** Populates the form, for example after restoring non-sensitive Activity state. */
    public void setServerConfig(ServerConfig config) {
        if (config == null) {
            return;
        }
        if (config.getProtocol() == Protocol.SMB) {
            smbRadio.setChecked(true);
        } else {
            webDavRadio.setChecked(true);
        }
        webDavUrl.setText(config.getWebDavUrl());
        smbHost.setText(config.getSmbHost());
        smbPort.setText(String.valueOf(config.getSmbPort()));
        smbShare.setText(config.getSmbShare());
        smbDomain.setText(config.getSmbDomain());
        username.setText(config.getUsername());
        password.setText(config.getPassword());
        remotePath.setText(config.getRemotePath());
    }

    /** Clears the password field before the Activity is backgrounded if credentials are not kept. */
    public void clearSensitiveFields() {
        password.setText("");
    }

    /**
     * Displays a folder returned from ACTION_OPEN_DOCUMENT_TREE. The host should separately call
     * takePersistableUriPermission when it receives the Activity result.
     */
    public void setSelectedLocalFolder(Uri folderUri, CharSequence displayName) {
        selectedLocalFolder = folderUri;
        if (folderUri == null) {
            selectedFolderText.setText(R.string.pc_folder_not_selected);
        } else if (TextUtils.isEmpty(displayName)) {
            selectedFolderText.setText(folderUri.toString());
        } else {
            selectedFolderText.setText(displayName);
        }
        selectedFolderText.setContentDescription(
                getResources().getString(
                        R.string.pc_a11y_selected_folder,
                        selectedFolderText.getText()));
        refreshActionAvailability();
    }

    public Uri getSelectedLocalFolder() {
        return selectedLocalFolder;
    }

    /** Updates the snapshot choices. Call showSuccess/showError separately to end a running task. */
    public void setSnapshots(List<RemoteSnapshot> remoteSnapshots) {
        if (remoteSnapshots == null || remoteSnapshots.isEmpty()) {
            snapshots = Collections.emptyList();
        } else {
            snapshots = Collections.unmodifiableList(new ArrayList<RemoteSnapshot>(remoteSnapshots));
        }
        selectedSnapshot = null;
        renderSnapshots();
        refreshActionAvailability();
    }

    /** Convenience for completing a successful snapshot-list request and rendering its result. */
    public void showSnapshots(List<RemoteSnapshot> remoteSnapshots) {
        setSnapshots(remoteSnapshots);
        showSuccess(
                getResources().getString(R.string.pc_status_snapshots_loaded, snapshots.size()));
    }

    public RemoteSnapshot getSelectedSnapshot() {
        return selectedSnapshot;
    }

    /** Shows an indeterminate, optionally cancellable operation. */
    public void showOperation(CharSequence message, boolean cancellable) {
        operationRunning = true;
        operationStatus.setText(
                TextUtils.isEmpty(message) ? textResource(R.string.pc_status_working) : message);
        operationStatus.setTextColor(color(R.color.pc_status_running));
        operationProgress.setVisibility(VISIBLE);
        operationProgress.setIndeterminate(true);
        operationDetail.setVisibility(GONE);
        cancelButton.setVisibility(cancellable ? VISIBLE : GONE);
        cancelButton.setEnabled(cancellable);
        refreshActionAvailability();
        announceStatus();
    }

    /** Updates a determinate operation. Values outside 0..100 are safely clamped. */
    public void showProgress(int percent, CharSequence detail) {
        operationRunning = true;
        int safePercent = Math.max(0, Math.min(100, percent));
        operationStatus.setText(
                getResources().getString(R.string.pc_status_progress, safePercent));
        operationStatus.setTextColor(color(R.color.pc_status_running));
        operationProgress.setVisibility(VISIBLE);
        operationProgress.setIndeterminate(false);
        operationProgress.setMax(100);
        operationProgress.setProgress(safePercent);
        if (TextUtils.isEmpty(detail)) {
            operationDetail.setVisibility(GONE);
        } else {
            operationDetail.setText(detail);
            operationDetail.setVisibility(VISIBLE);
        }
        refreshActionAvailability();
    }

    public void showSuccess(CharSequence message) {
        finishOperation(
                TextUtils.isEmpty(message) ? textResource(R.string.pc_status_success) : message,
                R.color.pc_status_success);
    }

    public void showError(CharSequence message) {
        finishOperation(
                TextUtils.isEmpty(message) ? textResource(R.string.pc_status_failed) : message,
                R.color.pc_status_error);
    }

    public void renderIdle() {
        operationRunning = false;
        operationStatus.setText(R.string.pc_status_idle);
        operationStatus.setTextColor(color(R.color.pc_text_secondary));
        operationProgress.setVisibility(GONE);
        operationDetail.setVisibility(GONE);
        cancelButton.setVisibility(GONE);
        refreshActionAvailability();
    }

    private void bindViews() {
        protocolGroup = findViewById(R.id.pc_protocol_group);
        webDavRadio = findViewById(R.id.pc_protocol_webdav);
        smbRadio = findViewById(R.id.pc_protocol_smb);
        webDavFields = findViewById(R.id.pc_webdav_fields);
        smbFields = findViewById(R.id.pc_smb_fields);
        webDavUrl = findViewById(R.id.pc_webdav_url);
        smbHost = findViewById(R.id.pc_smb_host);
        smbPort = findViewById(R.id.pc_smb_port);
        smbShare = findViewById(R.id.pc_smb_share);
        smbDomain = findViewById(R.id.pc_smb_domain);
        username = findViewById(R.id.pc_username);
        password = findViewById(R.id.pc_password);
        remotePath = findViewById(R.id.pc_remote_path);
        selectedFolderText = findViewById(R.id.pc_selected_folder);
        chooseFolderButton = findViewById(R.id.pc_choose_folder);
        testConnectionButton = findViewById(R.id.pc_test_connection);
        backupButton = findViewById(R.id.pc_start_backup);
        loadSnapshotsButton = findViewById(R.id.pc_load_snapshots);
        snapshotGroup = findViewById(R.id.pc_snapshot_group);
        snapshotEmptyText = findViewById(R.id.pc_snapshot_empty);
        restoreButton = findViewById(R.id.pc_restore_snapshot);
        operationStatus = findViewById(R.id.pc_operation_status);
        operationProgress = findViewById(R.id.pc_operation_progress);
        operationDetail = findViewById(R.id.pc_operation_detail);
        cancelButton = findViewById(R.id.pc_cancel_operation);
    }

    private void bindActions() {
        protocolGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                renderProtocol(checkedId == R.id.pc_protocol_smb ? Protocol.SMB : Protocol.WEBDAV);
            }
        });

        chooseFolderButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                if (listener == null) {
                    showError(textResource(R.string.pc_error_no_controller));
                    return;
                }
                listener.onChooseLocalFolder();
            }
        });

        testConnectionButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                ServerConfig config = validatedConfig();
                if (config == null || !ensureListener()) {
                    return;
                }
                showOperation(textResource(R.string.pc_status_testing), true);
                listener.onTestConnection(config);
            }
        });

        backupButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                ServerConfig config = validatedConfig();
                if (config == null || !validateLocalFolder() || !ensureListener()) {
                    return;
                }
                showOperation(textResource(R.string.pc_status_preparing_backup), true);
                listener.onStartBackup(config, selectedLocalFolder);
            }
        });

        loadSnapshotsButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                ServerConfig config = validatedConfig();
                if (config == null || !ensureListener()) {
                    return;
                }
                showOperation(textResource(R.string.pc_status_loading_snapshots), true);
                listener.onLoadRemoteSnapshots(config);
            }
        });

        restoreButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                ServerConfig config = validatedConfig();
                if (config == null || !validateLocalFolder()) {
                    return;
                }
                if (selectedSnapshot == null) {
                    showError(textResource(R.string.pc_error_snapshot_required));
                    return;
                }
                if (!ensureListener()) {
                    return;
                }
                showOperation(textResource(R.string.pc_status_preparing_restore), true);
                listener.onRestoreSnapshot(config, selectedLocalFolder, selectedSnapshot);
            }
        });

        cancelButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                if (listener == null) {
                    return;
                }
                cancelButton.setEnabled(false);
                operationStatus.setText(R.string.pc_status_cancelling);
                listener.onCancelOperation();
            }
        });
    }

    private void renderProtocol(Protocol protocol) {
        boolean webDav = protocol == Protocol.WEBDAV;
        webDavFields.setVisibility(webDav ? VISIBLE : GONE);
        smbFields.setVisibility(webDav ? GONE : VISIBLE);
        remotePath.setHint(
                webDav ? R.string.pc_hint_webdav_remote_path : R.string.pc_hint_smb_remote_path);
    }

    private void renderSnapshots() {
        snapshotGroup.removeAllViews();
        snapshotEmptyText.setVisibility(snapshots.isEmpty() ? VISIBLE : GONE);
        snapshotGroup.setVisibility(snapshots.isEmpty() ? GONE : VISIBLE);

        for (final RemoteSnapshot snapshot : snapshots) {
            RadioButton option = new RadioButton(getContext());
            option.setId(View.generateViewId());
            option.setLayoutParams(
                    new RadioGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            option.setPadding(0, dimension(R.dimen.pc_space_8), 0, dimension(R.dimen.pc_space_8));
            option.setText(snapshotLabel(snapshot));
            option.setTextColor(color(R.color.pc_text_primary));
            option.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            option.setMinHeight(dimension(R.dimen.pc_touch_target));
            option.setTag(snapshot);
            snapshotGroup.addView(option);
        }

        snapshotGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                View selectedView = group.findViewById(checkedId);
                Object tag = selectedView == null ? null : selectedView.getTag();
                selectedSnapshot = tag instanceof RemoteSnapshot ? (RemoteSnapshot) tag : null;
                refreshActionAvailability();
            }
        });
    }

    private CharSequence snapshotLabel(RemoteSnapshot snapshot) {
        if (TextUtils.isEmpty(snapshot.getDetails())) {
            return snapshot.getTitle();
        }
        return getResources().getString(
                R.string.pc_snapshot_label, snapshot.getTitle(), snapshot.getDetails());
    }

    private ServerConfig collectConfig() {
        if (getSelectedProtocol() == Protocol.WEBDAV) {
            return ServerConfig.webDav(
                    text(webDavUrl), text(username), rawText(password), text(remotePath));
        }
        return ServerConfig.smb(
                text(smbHost),
                parsedPort(),
                text(smbShare),
                text(smbDomain),
                text(username),
                rawText(password),
                text(remotePath));
    }

    private ServerConfig validatedConfig() {
        clearFieldErrors();
        if (getSelectedProtocol() == Protocol.WEBDAV) {
            String url = text(webDavUrl);
            Uri parsed = Uri.parse(url);
            boolean acceptedScheme = "http".equalsIgnoreCase(parsed.getScheme())
                    || "https".equalsIgnoreCase(parsed.getScheme());
            if (TextUtils.isEmpty(url)
                    || !acceptedScheme
                    || TextUtils.isEmpty(parsed.getHost())
                    || parsed.getUserInfo() != null
                    || parsed.getQuery() != null
                    || parsed.getFragment() != null) {
                return rejectField(webDavUrl, R.string.pc_error_webdav_url);
            }
            if (text(username).indexOf(':') >= 0) {
                return rejectField(username, R.string.pc_error_webdav_username);
            }
        } else {
            String host = text(smbHost);
            if (TextUtils.isEmpty(host)
                    || host.indexOf('/') >= 0
                    || host.indexOf('\\') >= 0
                    || host.indexOf('@') >= 0
                    || host.indexOf('?') >= 0
                    || host.indexOf('#') >= 0) {
                return rejectField(smbHost, R.string.pc_error_smb_host);
            }
            String share = text(smbShare);
            if (TextUtils.isEmpty(share)
                    || share.indexOf('/') >= 0
                    || share.indexOf('\\') >= 0) {
                return rejectField(smbShare, R.string.pc_error_smb_share);
            }
            int port = parsedPort();
            if (port < 1 || port > 65535) {
                return rejectField(smbPort, R.string.pc_error_smb_port);
            }
        }
        return collectConfig();
    }

    private ServerConfig rejectField(EditText field, int errorMessage) {
        field.setError(textResource(errorMessage));
        field.requestFocus();
        showError(textResource(errorMessage));
        return null;
    }

    private boolean validateLocalFolder() {
        if (selectedLocalFolder != null) {
            return true;
        }
        showError(textResource(R.string.pc_error_folder_required));
        chooseFolderButton.requestFocus();
        return false;
    }

    private boolean ensureListener() {
        if (listener != null) {
            return true;
        }
        showError(textResource(R.string.pc_error_no_controller));
        return false;
    }

    private void clearFieldErrors() {
        webDavUrl.setError(null);
        smbHost.setError(null);
        smbPort.setError(null);
        smbShare.setError(null);
        username.setError(null);
        password.setError(null);
    }

    private int parsedPort() {
        String value = text(smbPort);
        if (TextUtils.isEmpty(value)) {
            return 445;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private void finishOperation(CharSequence message, int colorResource) {
        operationRunning = false;
        operationStatus.setText(message);
        operationStatus.setTextColor(color(colorResource));
        operationProgress.setVisibility(GONE);
        operationDetail.setVisibility(GONE);
        cancelButton.setVisibility(GONE);
        refreshActionAvailability();
        announceStatus();
    }

    private void refreshActionAvailability() {
        boolean enabled = !operationRunning;
        setEnabledRecursively(protocolGroup, enabled);
        setEnabledRecursively(webDavFields, enabled);
        setEnabledRecursively(smbFields, enabled);
        username.setEnabled(enabled);
        password.setEnabled(enabled);
        remotePath.setEnabled(enabled);
        chooseFolderButton.setEnabled(enabled);
        testConnectionButton.setEnabled(enabled);
        backupButton.setEnabled(enabled && selectedLocalFolder != null);
        loadSnapshotsButton.setEnabled(enabled);
        setEnabledRecursively(snapshotGroup, enabled);
        restoreButton.setEnabled(
                enabled && selectedLocalFolder != null && selectedSnapshot != null);
    }

    private void setEnabledRecursively(View view, boolean enabled) {
        view.setEnabled(enabled);
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            setEnabledRecursively(group.getChildAt(index), enabled);
        }
    }

    private void announceStatus() {
        operationStatus.sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT);
    }

    @SuppressWarnings("deprecation")
    private int color(int colorResource) {
        return getResources().getColor(colorResource);
    }

    private int dimension(int dimensionResource) {
        return getResources().getDimensionPixelSize(dimensionResource);
    }

    private CharSequence textResource(int stringResource) {
        return getResources().getText(stringResource);
    }

    private static String text(EditText input) {
        return input.getText() == null ? "" : input.getText().toString().trim();
    }

    private static String rawText(EditText input) {
        return input.getText() == null ? "" : input.getText().toString();
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }
}
