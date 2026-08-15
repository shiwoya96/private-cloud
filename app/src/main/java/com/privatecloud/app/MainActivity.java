package com.privatecloud.app;

import android.app.Activity;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.format.Formatter;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.work.Constraints;
import androidx.work.BackoffPolicy;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.privatecloud.app.backup.ProgressListener;
import com.privatecloud.app.config.ConnectionSettings;
import com.privatecloud.app.config.BackupHistoryRepository;
import com.privatecloud.app.config.BackupSchedule;
import com.privatecloud.app.config.ScheduleRepository;
import com.privatecloud.app.config.SettingsRepository;
import com.privatecloud.app.config.SnapshotCache;
import com.privatecloud.app.config.SnapshotContentsCache;
import com.privatecloud.app.local.SafTree;
import com.privatecloud.app.model.SnapshotInfo;
import com.privatecloud.app.service.CloudWorker;
import com.privatecloud.app.service.BackupScheduler;
import com.privatecloud.app.ui.BackupDashboardView;

import java.security.GeneralSecurityException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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
        restoreScheduleAndHistory();
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
    public void onSaveConfiguration(BackupDashboardView.ServerConfig config) {
        try {
            settingsRepository.save(fromUi(config), dashboard.getSelectedLocalFolder());
            new ScheduleRepository(this).saveExclusions(
                    dashboard.getPlanId(), dashboard.getExclusions());
            settingsRepository.saveRecoveryKey(
                    dashboard.getPlanId(), dashboard.getRecoveryKey());
            dashboard.setSavedPlans(settingsRepository.listPlanIds());
            dashboard.showSuccess("已安全保存服务器配置");
        } catch (GeneralSecurityException failure) {
            dashboard.showError("无法安全保存服务器配置，请重试");
        } catch (IllegalArgumentException invalid) {
            dashboard.showError("服务器配置无效：" + safeMessage(invalid));
        }
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
    public void onSaveSchedule(
            BackupDashboardView.ServerConfig config,
            Uri localFolder,
            boolean enabled,
            int intervalHours,
            boolean unmeteredOnly,
            boolean chargingOnly) {
        try {
            BackupSchedule schedule = new BackupSchedule(
                    enabled, intervalHours, unmeteredOnly, chargingOnly);
            ConnectionSettings settings = fromUi(config);
            new BackupHistoryRepository(this).enableRecording();
            BackupScheduler.apply(
                    this, schedule, settings, localFolder, dashboard.getExclusions(),
                    dashboard.getRecoveryKey(), dashboard.getRetentionCount());
            if (!new ScheduleRepository(this).save(dashboard.getPlanId(), schedule)) {
                throw new GeneralSecurityException("Unable to persist schedule");
            }
            dashboard.showSuccess(enabled ? "已启用自动备份" : "已关闭自动备份");
        } catch (GeneralSecurityException failure) {
            dashboard.showError("无法安全保存自动备份设置，请重试");
        } catch (IllegalArgumentException invalid) {
            dashboard.showError(safeMessage(invalid));
        }
    }

    @Override
    public void onLoadRemoteSnapshots(BackupDashboardView.ServerConfig config) {
        enqueue(config, dashboard.getSelectedLocalFolder(), CloudWorker.OP_LIST, null);
    }

    @Override
    public void onRestoreSnapshot(
            BackupDashboardView.ServerConfig config,
            Uri localFolder,
            BackupDashboardView.RemoteSnapshot snapshot,
            String selectionPath) {
        enqueue(config, localFolder, CloudWorker.OP_RESTORE, snapshot.getId(), selectionPath);
    }

    @Override
    public void onDeleteSnapshot(
            BackupDashboardView.ServerConfig config,
            BackupDashboardView.RemoteSnapshot snapshot) {
        enqueue(config, dashboard.getSelectedLocalFolder(), CloudWorker.OP_DELETE, snapshot.getId());
    }

    @Override
    public void onInspectSnapshot(
            BackupDashboardView.ServerConfig config,
            BackupDashboardView.RemoteSnapshot snapshot) {
        enqueue(config, dashboard.getSelectedLocalFolder(), CloudWorker.OP_INSPECT, snapshot.getId());
    }

    @Override
    public void onApplyRetention(BackupDashboardView.ServerConfig config, int keepCount) {
        new ScheduleRepository(this).saveRetentionCount(dashboard.getPlanId(), keepCount);
        enqueue(config, dashboard.getSelectedLocalFolder(), CloudWorker.OP_PRUNE,
                String.valueOf(keepCount));
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
            dashboard.setPlanId(saved.getPlanId());
            dashboard.setExclusions(
                    new ScheduleRepository(this).loadExclusions(saved.getPlanId()));
            dashboard.setRecoveryKey(settingsRepository.loadRecoveryKey(saved.getPlanId()));
            dashboard.setSavedPlans(settingsRepository.listPlanIds());
            dashboard.setRetentionCount(
                    new ScheduleRepository(this).loadRetentionCount(saved.getPlanId()));
            Uri treeUri = settingsRepository.loadTreeUri();
            if (treeUri != null) dashboard.setSelectedLocalFolder(treeUri, treeUri.getLastPathSegment());
        } catch (GeneralSecurityException damagedCredentials) {
            dashboard.showError("本机保存的凭据无法解密，请重新填写服务器信息");
        } catch (IllegalArgumentException damagedSettings) {
            dashboard.showError("保存的服务器配置无效，请重新填写");
        }
    }

    @Override
    public void onLoadPlan(String planId) {
        try {
            ConnectionSettings saved = settingsRepository.loadPlan(planId);
            dashboard.setServerConfig(toUi(saved));
            dashboard.setPlanId(saved.getPlanId());
            dashboard.setExclusions(new ScheduleRepository(this).loadExclusions(planId));
            dashboard.setRecoveryKey(settingsRepository.loadRecoveryKey(planId));
            dashboard.setRetentionCount(
                    new ScheduleRepository(this).loadRetentionCount(planId));
            BackupSchedule planSchedule = new ScheduleRepository(this).load(planId);
            dashboard.setSchedule(
                    planSchedule.isEnabled(), planSchedule.getIntervalHours(),
                    planSchedule.isUnmeteredOnly(), planSchedule.isChargingOnly());
            Uri treeUri = settingsRepository.loadPlanTreeUri(planId);
            dashboard.setSelectedLocalFolder(
                    treeUri, treeUri == null ? null : treeUri.getLastPathSegment());
            dashboard.showSuccess("已加载备份方案");
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            dashboard.showError("无法加载该备份方案");
        }
    }

    @Override
    public void onDeletePlan(String planId) {
        try {
            Uri treeUri = settingsRepository.loadPlanTreeUri(planId);
            boolean sharedTreeUri = isTreeUriUsedByAnotherPlan(planId, treeUri);
            settingsRepository.deletePlan(planId);
            new ScheduleRepository(this).deletePlan(planId);
            workManager.cancelUniqueWork(BackupScheduler.UNIQUE_WORK_NAME + ":" + planId);
            if (treeUri != null && !sharedTreeUri) releaseTreePermission(treeUri);
            dashboard.setServerConfig(BackupDashboardView.ServerConfig.webDav("", "", "", ""));
            dashboard.setPlanId("default-plan");
            dashboard.setSelectedLocalFolder(null, null);
            dashboard.setExclusions("");
            dashboard.setRecoveryKey("");
            dashboard.setSchedule(false, 24, true, false);
            dashboard.setSavedPlans(settingsRepository.listPlanIds());
            dashboard.showSuccess("已删除本地方案；远端快照未删除");
        } catch (GeneralSecurityException | IllegalArgumentException failure) {
            dashboard.showError("无法删除该备份方案");
        }
    }

    private boolean isTreeUriUsedByAnotherPlan(String deletedPlanId, Uri treeUri) {
        if (treeUri == null) return false;
        for (String otherPlanId : settingsRepository.listPlanIds()) {
            if (deletedPlanId.equals(otherPlanId)) continue;
            try {
                if (treeUri.equals(settingsRepository.loadPlanTreeUri(otherPlanId))) return true;
            } catch (GeneralSecurityException damagedPlan) {
                // Keep the grant when another plan cannot be inspected safely.
                return true;
            }
        }
        return false;
    }

    private boolean releaseTreePermission(Uri treeUri) {
        int flags = 0;
        for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if (!treeUri.equals(permission.getUri())) continue;
            if (permission.isReadPermission()) flags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (permission.isWritePermission()) flags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        }
        if (flags == 0) return true;
        try {
            getContentResolver().releasePersistableUriPermission(treeUri, flags);
            return true;
        } catch (SecurityException alreadyRevoked) {
            // The provider or user may already have revoked this grant.
            return false;
        }
    }

    @Override
    public void onClearLocalData() {
        stopRecoveringWork();
        stopObservingWork();
        activeWorkId = null;
        activeOperation = null;
        workManager.cancelAllWorkByTag(CloudWorker.TAG);
        ArrayList<Uri> treeUris = new ArrayList<Uri>();
        for (UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
            if (!treeUris.contains(permission.getUri())) treeUris.add(permission.getUri());
        }
        boolean cleared = true;
        try {
            settingsRepository.clearAll();
        } catch (GeneralSecurityException failure) {
            cleared = false;
        }
        if (!new BackupHistoryRepository(this).clearAndDisable()) cleared = false;
        if (!new SnapshotContentsCache(this).clear()) cleared = false;
        if (!new SnapshotCache(this).clear()) cleared = false;
        if (!new ScheduleRepository(this).clear()) cleared = false;
        for (Uri treeUri : treeUris) {
            if (!releaseTreePermission(treeUri)) cleared = false;
        }
        dashboard.setServerConfig(BackupDashboardView.ServerConfig.webDav("", "", "", ""));
        dashboard.setPlanId("default-plan");
        dashboard.setSelectedLocalFolder(null, null);
        dashboard.setExclusions("");
        dashboard.setRecoveryKey("");
        dashboard.setSchedule(false, 24, true, false);
        dashboard.setBackupHistory(null);
        dashboard.setSavedPlans(java.util.Collections.<String>emptyList());
        if (cleared) {
            dashboard.showSuccess("已清除本地配置、历史和目录授权");
        } else {
            dashboard.showError("无法完整清除本地配置或目录授权");
        }
    }

    private void restoreScheduleAndHistory() {
        BackupSchedule schedule = new ScheduleRepository(this).load(dashboard.getPlanId());
        dashboard.setSchedule(
                schedule.isEnabled(), schedule.getIntervalHours(),
                schedule.isUnmeteredOnly(), schedule.isChargingOnly());
        refreshHistory();
    }

    private void refreshHistory() {
        List<BackupHistoryRepository.Entry> history =
                new BackupHistoryRepository(this).loadRecent(5);
        if (history.isEmpty()) {
            dashboard.setBackupHistory(null);
            return;
        }
        StringBuilder summary = new StringBuilder();
        for (BackupHistoryRepository.Entry entry : history) {
            if (summary.length() > 0) summary.append("\n\n");
            String operation = CloudWorker.OP_BACKUP.equals(entry.operation) ? "备份"
                    : CloudWorker.OP_RESTORE.equals(entry.operation) ? "恢复" : "任务";
            String time = DateFormat.getDateTimeInstance(
                    DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(entry.finishedAt));
            summary.append(time).append(" · ").append(operation)
                    .append(entry.success ? "成功" : "失败")
                    .append("\n").append(entry.detail);
        }
        dashboard.setBackupHistory(summary);
    }

    private void enqueue(
            BackupDashboardView.ServerConfig config,
            Uri localFolder,
            String operation,
            String snapshotId) {
        enqueue(config, localFolder, operation, snapshotId, "");
    }

    private void enqueue(
            BackupDashboardView.ServerConfig config, Uri localFolder, String operation,
            String snapshotId, String selectionPath) {
        try {
            ConnectionSettings settings = fromUi(config);
            new BackupHistoryRepository(this).enableRecording();
            settingsRepository.saveRecoveryKey(settings.getPlanId(), dashboard.getRecoveryKey());
            ScheduleRepository planPreferences = new ScheduleRepository(this);
            planPreferences.saveExclusions(settings.getPlanId(), dashboard.getExclusions());
            planPreferences.saveRetentionCount(
                    settings.getPlanId(), dashboard.getRetentionCount());
            UUID requestId = UUID.randomUUID();
            String jobKey = requestId.toString();
            String encryptedSpec = settingsRepository.prepareOperation(
                    jobKey, settings, localFolder, operation, snapshotId, selectionPath,
                    dashboard.getExclusions(), dashboard.getRecoveryKey(),
                    dashboard.getRetentionCount());
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
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
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
                    || CloudWorker.OP_RESTORE.equals(operation)
                    || CloudWorker.OP_DELETE.equals(operation)
                    || CloudWorker.OP_PRUNE.equals(operation)
                    || CloudWorker.OP_INSPECT.equals(operation)) {
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

        boolean refreshSnapshots = false;
        if (state == WorkInfo.State.SUCCEEDED) {
            String message = info.getOutputData().getString(CloudWorker.KEY_MESSAGE);
            if (CloudWorker.OP_LIST.equals(operation)) {
                dashboard.showSnapshots(loadCachedSnapshots());
            } else if (CloudWorker.OP_INSPECT.equals(operation)) {
                BackupDashboardView.RemoteSnapshot snapshot = dashboard.getSelectedSnapshot();
                if (snapshot != null) {
                    dashboard.setSnapshotContents(new SnapshotContentsCache(this).load(
                            dashboard.getPlanId(), snapshot.getId()));
                }
                dashboard.showSuccess(emptyFallback(message, "快照内容读取完成"));
            } else {
                dashboard.showSuccess(emptyFallback(message, "任务完成"));
                refreshSnapshots = CloudWorker.OP_DELETE.equals(operation)
                        || CloudWorker.OP_PRUNE.equals(operation);
            }
        } else if (state == WorkInfo.State.CANCELLED) {
            new BackupHistoryRepository(this).record(operation, false, "任务已取消");
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
        refreshHistory();
        if (refreshSnapshots) {
            new SnapshotCache(this).save(
                    settingsRepository.getOrCreatePlanId(),
                    java.util.Collections.<SnapshotInfo>emptyList());
            dashboard.setSnapshots(
                    java.util.Collections.<BackupDashboardView.RemoteSnapshot>emptyList());
            enqueue(dashboard.getServerConfig(), dashboard.getSelectedLocalFolder(),
                    CloudWorker.OP_LIST, null);
        }
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
                dashboard.getPlanId());
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
