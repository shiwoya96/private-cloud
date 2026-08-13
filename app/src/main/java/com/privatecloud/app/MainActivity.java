package com.privatecloud.app;

import android.app.Activity;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.format.Formatter;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.privatecloud.app.backup.ProgressListener;
import com.privatecloud.app.config.ConnectionSettings;
import com.privatecloud.app.config.SettingsRepository;
import com.privatecloud.app.config.SnapshotCache;
import com.privatecloud.app.local.SafTree;
import com.privatecloud.app.model.SnapshotInfo;
import com.privatecloud.app.service.CloudWorker;
import com.privatecloud.app.ui.BackupDashboardView;

import java.security.GeneralSecurityException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** Single-screen controller for directory authorization and reliable WorkManager transfers. */
public final class MainActivity extends Activity implements BackupDashboardView.Listener {
    private static final int REQUEST_DIRECTORY = 4101;
    private static final String STATE_WORK_ID = "work_id";
    private static final String STATE_OPERATION = "operation";

    private BackupDashboardView dashboard;
    private SettingsRepository settingsRepository;
    private WorkManager workManager;
    private UUID activeWorkId;
    private String activeOperation;
    private LiveData<WorkInfo> observedWork;
    private Observer<WorkInfo> workObserver;
    private LiveData<List<WorkInfo>> recoveryWork;
    private Observer<List<WorkInfo>> recoveryObserver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settingsRepository = new SettingsRepository(this);
        workManager = WorkManager.getInstance(getApplicationContext());
        dashboard = new BackupDashboardView(this);
        dashboard.setListener(this);
        setContentView(dashboard);
        restoreSavedConfiguration();
        requestNotificationPermissionIfNeeded();

        boolean restoredWork = false;
        if (savedInstanceState != null) {
            String workId = savedInstanceState.getString(STATE_WORK_ID);
            activeOperation = savedInstanceState.getString(STATE_OPERATION);
            if (workId != null && activeOperation != null) {
                try {
                    observeWork(UUID.fromString(workId), activeOperation, true);
                    restoredWork = true;
                } catch (IllegalArgumentException ignored) {
                    activeWorkId = null;
                    activeOperation = null;
                }
            }
        }
        if (!restoredWork) recoverUniqueWork();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 4102);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (activeWorkId != null && activeOperation != null) {
            outState.putString(STATE_WORK_ID, activeWorkId.toString());
            outState.putString(STATE_OPERATION, activeOperation);
        }
    }

    @Override
    protected void onDestroy() {
        stopRecoveringWork();
        stopObservingWork();
        if (isFinishing() && dashboard != null) dashboard.clearSensitiveFields();
        super.onDestroy();
    }

    @Override
    public void onChooseLocalFolder() {
        startActivityForResult(
                SafTree.newOpenDocumentTreeIntent(dashboard.getSelectedLocalFolder()),
                REQUEST_DIRECTORY);
    }

    @Override
    public void onTestConnection(BackupDashboardView.ServerConfig config) {
        enqueue(config, dashboard.getSelectedLocalFolder(), CloudWorker.OP_TEST, null);
    }

    @Override
    public void onStartBackup(BackupDashboardView.ServerConfig config, Uri localFolder) {
        enqueue(config, localFolder, CloudWorker.OP_BACKUP, null);
    }

    @Override
    public void onLoadRemoteSnapshots(BackupDashboardView.ServerConfig config) {
        enqueue(config, dashboard.getSelectedLocalFolder(), CloudWorker.OP_LIST, null);
    }

    @Override
    public void onRestoreSnapshot(
            BackupDashboardView.ServerConfig config,
            Uri localFolder,
            BackupDashboardView.RemoteSnapshot snapshot) {
        enqueue(config, localFolder, CloudWorker.OP_RESTORE, snapshot.getId());
    }

    @Override
    public void onCancelOperation() {
        if (activeWorkId != null) workManager.cancelWorkById(activeWorkId);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_DIRECTORY || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        Uri treeUri = data.getData();
        try {
            int flags = SafTree.persistPickerPermission(
                    getContentResolver(), treeUri, data.getFlags());
            settingsRepository.saveTreeUri(treeUri);
            String label = treeUri.getLastPathSegment();
            dashboard.setSelectedLocalFolder(treeUri, label);
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
                dashboard.showError("目录只授予了读取权限：可以备份，但无法恢复到此目录");
            } else {
                dashboard.showSuccess("已保存手机目录授权");
            }
        } catch (GeneralSecurityException failure) {
            dashboard.showError("无法保存目录设置，请重试");
        } catch (java.io.IOException failure) {
            dashboard.showError(failure.getMessage());
        }
    }

    private void restoreSavedConfiguration() {
        try {
            ConnectionSettings saved = settingsRepository.load();
            dashboard.setServerConfig(toUi(saved));
            Uri treeUri = settingsRepository.loadTreeUri();
            if (treeUri != null) dashboard.setSelectedLocalFolder(treeUri, treeUri.getLastPathSegment());
        } catch (GeneralSecurityException damagedCredentials) {
            dashboard.showError("本机保存的凭据无法解密，请重新填写服务器信息");
        } catch (IllegalArgumentException damagedSettings) {
            dashboard.showError("保存的服务器配置无效，请重新填写");
        }
    }

    private void enqueue(
            BackupDashboardView.ServerConfig config,
            Uri localFolder,
            String operation,
            String snapshotId) {
        try {
            ConnectionSettings settings = fromUi(config);
            UUID requestId = UUID.randomUUID();
            String jobKey = requestId.toString();
            String encryptedSpec = settingsRepository.prepareOperation(
                    jobKey, settings, localFolder, operation, snapshotId);
            Data input = new Data.Builder()
                    .putString(CloudWorker.KEY_ENCRYPTED_SPEC, encryptedSpec)
                    .build();
            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CloudWorker.class)
                    .setId(requestId)
                    .setInputData(input)
                    .setConstraints(constraints)
                    .addTag(CloudWorker.TAG)
                    .addTag(CloudWorker.TAG + ":" + operation)
                    .build();
            workManager.enqueueUniqueWork(
                    CloudWorker.UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request);
            observeWork(requestId, operation);
        } catch (GeneralSecurityException failure) {
            dashboard.showError("无法安全保存服务器密码，请重试");
        } catch (IllegalArgumentException invalid) {
            dashboard.showError("服务器配置无效：" + safeMessage(invalid));
        } catch (RuntimeException unableToEnqueue) {
            dashboard.showError("无法提交后台任务：" + safeMessage(unableToEnqueue));
        }
    }

    private void observeWork(final UUID workId, final String operation) {
        observeWork(workId, operation, false);
    }

    private void observeWork(
            final UUID workId, final String operation, final boolean recoverIfMissing) {
        stopRecoveringWork();
        stopObservingWork();
        activeWorkId = workId;
        activeOperation = operation;
        dashboard.showOperation("正在恢复后台任务状态…", true);
        observedWork = workManager.getWorkInfoByIdLiveData(workId);
        workObserver = new Observer<WorkInfo>() {
            @Override
            public void onChanged(WorkInfo info) {
                if (!workId.equals(activeWorkId)) return;
                if (info == null) {
                    if (!recoverIfMissing) return;
                    activeWorkId = null;
                    activeOperation = null;
                    stopObservingWork();
                    recoverUniqueWork();
                    return;
                }
                renderWorkInfo(info, operation);
            }
        };
        observedWork.observeForever(workObserver);
    }

    /** Reconnects to WorkManager's durable unique work after a process-death cold start. */
    private void recoverUniqueWork() {
        stopRecoveringWork();
        dashboard.showOperation("正在检查后台任务…", false);
        recoveryWork = workManager.getWorkInfosForUniqueWorkLiveData(
                CloudWorker.UNIQUE_WORK_NAME);
        recoveryObserver = new Observer<List<WorkInfo>>() {
            @Override
            public void onChanged(List<WorkInfo> workInfos) {
                if (workInfos == null) return;
                WorkInfo unfinished = findUnfinishedWork(workInfos);
                if (unfinished == null) {
                    dashboard.renderIdle();
                    return;
                }
                stopRecoveringWork();
                String operation = operationFromTags(unfinished);
                observeWork(unfinished.getId(), operation);
            }
        };
        recoveryWork.observeForever(recoveryObserver);
    }

    private static WorkInfo findUnfinishedWork(List<WorkInfo> workInfos) {
        WorkInfo candidate = null;
        for (WorkInfo info : workInfos) {
            if (info == null || info.getState().isFinished()) continue;
            // Prefer the work that is already running if a replacement transition briefly
            // exposes more than one unfinished record.
            if (info.getState() == WorkInfo.State.RUNNING) return info;
            candidate = info;
        }
        return candidate;
    }

    private static String operationFromTags(WorkInfo info) {
        String prefix = CloudWorker.TAG + ":";
        for (String tag : info.getTags()) {
            if (!tag.startsWith(prefix)) continue;
            String operation = tag.substring(prefix.length());
            if (CloudWorker.OP_TEST.equals(operation)
                    || CloudWorker.OP_BACKUP.equals(operation)
                    || CloudWorker.OP_LIST.equals(operation)
                    || CloudWorker.OP_RESTORE.equals(operation)) {
                return operation;
            }
        }
        return "";
    }

    private void stopRecoveringWork() {
        if (recoveryWork != null && recoveryObserver != null) {
            recoveryWork.removeObserver(recoveryObserver);
        }
        recoveryWork = null;
        recoveryObserver = null;
    }

    private void renderWorkInfo(WorkInfo info, String operation) {
        WorkInfo.State state = info.getState();
        if (state == WorkInfo.State.ENQUEUED || state == WorkInfo.State.BLOCKED) {
            dashboard.showOperation("等待可用网络…", true);
            return;
        }
        if (state == WorkInfo.State.RUNNING) {
            Data progress = info.getProgress();
            int percent = progress.getInt(CloudWorker.KEY_PERCENT, -1);
            String stage = progress.getString(CloudWorker.KEY_STAGE);
            if (percent >= 0) {
                dashboard.showProgress(percent, stageLabel(stage));
            } else {
                dashboard.showOperation(stageLabel(stage), true);
            }
            return;
        }

        if (state == WorkInfo.State.SUCCEEDED) {
            String message = info.getOutputData().getString(CloudWorker.KEY_MESSAGE);
            if (CloudWorker.OP_LIST.equals(operation)) {
                dashboard.showSnapshots(loadCachedSnapshots());
            } else {
                dashboard.showSuccess(emptyFallback(message, "任务完成"));
            }
        } else if (state == WorkInfo.State.CANCELLED) {
            dashboard.showError("任务已取消；已完成的旧快照不会受到影响");
        } else if (state == WorkInfo.State.FAILED) {
            dashboard.showError(emptyFallback(
                    info.getOutputData().getString(CloudWorker.KEY_MESSAGE), "任务失败"));
        } else {
            return;
        }
        activeWorkId = null;
        activeOperation = null;
        stopObservingWork();
    }

    private List<BackupDashboardView.RemoteSnapshot> loadCachedSnapshots() {
        List<SnapshotInfo> snapshots = new SnapshotCache(this).load(
                settingsRepository.getOrCreatePlanId());
        ArrayList<BackupDashboardView.RemoteSnapshot> items =
                new ArrayList<BackupDashboardView.RemoteSnapshot>(snapshots.size());
        DateFormat dateFormat = DateFormat.getDateTimeInstance(
                DateFormat.MEDIUM, DateFormat.SHORT);
        for (SnapshotInfo snapshot : snapshots) {
            String title = dateFormat.format(new Date(snapshot.getCreatedAt()));
            String details = snapshot.getSourceName() + " · " + snapshot.getFileCount()
                    + " 个文件 · " + Formatter.formatFileSize(this, snapshot.getTotalBytes());
            items.add(new BackupDashboardView.RemoteSnapshot(
                    snapshot.getSnapshotId(), title, details));
        }
        return items;
    }

    private void stopObservingWork() {
        if (observedWork != null && workObserver != null) {
            observedWork.removeObserver(workObserver);
        }
        observedWork = null;
        workObserver = null;
    }

    private ConnectionSettings fromUi(BackupDashboardView.ServerConfig config) {
        ConnectionSettings.Protocol protocol = config.getProtocol()
                == BackupDashboardView.Protocol.SMB
                ? ConnectionSettings.Protocol.SMB : ConnectionSettings.Protocol.WEBDAV;
        return new ConnectionSettings(
                protocol,
                config.getWebDavUrl(),
                config.getSmbHost(),
                config.getSmbPort(),
                config.getSmbShare(),
                config.getSmbDomain(),
                config.getUsername(),
                config.getPassword(),
                config.getRemotePath(),
                settingsRepository.getOrCreatePlanId());
    }

    private static BackupDashboardView.ServerConfig toUi(ConnectionSettings settings) {
        if (settings.getProtocol() == ConnectionSettings.Protocol.SMB) {
            return BackupDashboardView.ServerConfig.smb(
                    settings.getSmbHost(),
                    settings.getSmbPort(),
                    settings.getSmbShare(),
                    settings.getSmbDomain(),
                    settings.getUsername(),
                    settings.getPassword(),
                    settings.getRemotePath());
        }
        return BackupDashboardView.ServerConfig.webDav(
                settings.getWebDavUrl(),
                settings.getUsername(),
                settings.getPassword(),
                settings.getRemotePath());
    }

    private static String stageLabel(String rawStage) {
        if (rawStage == null) return "正在准备任务…";
        try {
            ProgressListener.Stage stage = ProgressListener.Stage.valueOf(rawStage);
            switch (stage) {
                case SCANNING: return "正在扫描手机目录…";
                case UPLOADING: return "正在上传文件…";
                case COMMITTING: return "正在提交完整快照…";
                case LISTING: return "正在验证远端快照…";
                case PREPARING_RESTORE: return "正在创建新的恢复目录…";
                case DOWNLOADING: return "正在下载并校验文件…";
                case FINISHED: return "正在完成任务…";
                default: return "正在处理…";
            }
        } catch (IllegalArgumentException unknown) {
            return "正在处理…";
        }
    }

    private static CharSequence emptyFallback(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    private static String safeMessage(Throwable error) {
        String value = error.getMessage();
        return value == null || value.trim().isEmpty() ? "请检查输入" : value;
    }
}
