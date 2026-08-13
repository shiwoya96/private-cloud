package com.privatecloud.app.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.privatecloud.app.MainActivity;
import com.privatecloud.app.backup.BackupEngine;
import com.privatecloud.app.backup.CancellationToken;
import com.privatecloud.app.backup.OperationCancelledException;
import com.privatecloud.app.backup.ProgressListener;
import com.privatecloud.app.backup.RestoreEngine;
import com.privatecloud.app.backup.RestoreResult;
import com.privatecloud.app.backup.SnapshotRepository;
import com.privatecloud.app.config.ConnectionSettings;
import com.privatecloud.app.config.SettingsRepository;
import com.privatecloud.app.config.SnapshotCache;
import com.privatecloud.app.local.SafTree;
import com.privatecloud.app.model.SnapshotInfo;
import com.privatecloud.app.remote.RemoteEntry;
import com.privatecloud.app.remote.RemoteStore;
import com.privatecloud.app.remote.RemoteStoreException;
import com.privatecloud.app.remote.RemoteStoreFactory;

import java.io.IOException;
import java.io.FileNotFoundException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** Reliable, cancellable entry point for test, backup, snapshot-list and restore operations. */
public final class CloudWorker extends Worker {
    public static final String UNIQUE_WORK_NAME = "private-cloud-active-operation";
    public static final String TAG = "private-cloud-operation";

    public static final String KEY_OPERATION = "operation";
    public static final String KEY_SNAPSHOT_ID = "snapshot_id";
    public static final String KEY_PERCENT = "percent";
    public static final String KEY_DETAIL = "detail";
    public static final String KEY_STAGE = "stage";
    public static final String KEY_MESSAGE = "message";
    public static final String KEY_RESULT_SNAPSHOT_ID = "result_snapshot_id";

    public static final String OP_TEST = "test";
    public static final String OP_BACKUP = "backup";
    public static final String OP_LIST = "list";
    public static final String OP_RESTORE = "restore";

    private static final String CHANNEL_ID = "private_cloud_transfers";
    private static final int NOTIFICATION_ID = 7401;

    public CloudWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull
    @Override
    public Result doWork() {
        String operation = getInputData().getString(KEY_OPERATION);
        if (!isKnownOperation(operation)) {
            return failure("未知任务类型");
        }

        SettingsRepository settingsRepository = new SettingsRepository(getApplicationContext());
        ConnectionSettings settings = null;
        try {
            settings = settingsRepository.load();
            if (OP_BACKUP.equals(operation) || OP_RESTORE.equals(operation)) {
                startForeground(operation);
            }
            CancellationToken cancellation = cancellationToken();
            ProgressListener progress = progressListener();
            try (RemoteStore store = RemoteStoreFactory.open(settings)) {
                if (OP_TEST.equals(operation)) {
                    cancellation.throwIfCancellationRequested();
                    RemoteEntry root;
                    try {
                        root = store.stat("");
                    } catch (FileNotFoundException missingConfiguredDirectory) {
                        store.createDirectories("");
                        root = store.stat("");
                    }
                    if (!root.isDirectory()) {
                        throw new IOException("配置的远端位置不是目录");
                    }
                    return success("连接成功");
                }

                if (OP_LIST.equals(operation)) {
                    List<SnapshotInfo> snapshots = new SnapshotRepository(store).listCommitted(
                            settings.getPlanId(), cancellation, progress);
                    new SnapshotCache(getApplicationContext()).save(
                            settings.getPlanId(), snapshots);
                    return success("已读取 " + snapshots.size() + " 个可用快照");
                }

                Uri treeUri = settingsRepository.loadTreeUri();
                if (treeUri == null) {
                    throw new IOException("手机目录授权不存在，请重新选择目录");
                }
                SafTree tree = new SafTree(getApplicationContext(), treeUri);
                if (OP_BACKUP.equals(operation)) {
                    SnapshotInfo snapshot = new BackupEngine(store).backup(
                            settings.getPlanId(), tree, cancellation, progress);
                    return Result.success(new Data.Builder()
                            .putString(KEY_MESSAGE, "备份完成：" + snapshot.getFileCount() + " 个文件")
                            .putString(KEY_RESULT_SNAPSHOT_ID, snapshot.getSnapshotId())
                            .build());
                }

                String snapshotId = getInputData().getString(KEY_SNAPSHOT_ID);
                if (snapshotId == null || snapshotId.isEmpty()) {
                    throw new IOException("没有指定要恢复的快照");
                }
                RestoreResult restored = new RestoreEngine(store).restore(
                        settings.getPlanId(), snapshotId, tree, cancellation, progress);
                return success("已恢复 " + restored.getRestoredFiles()
                        + " 个文件到新目录 " + restored.getDirectoryName());
            }
        } catch (OperationCancelledException cancelled) {
            return failure("任务已取消");
        } catch (GeneralSecurityException security) {
            return failure("无法解密本机保存的凭据，请重新填写服务器密码");
        } catch (IOException failure) {
            return failure(safeError(failure, settings));
        } catch (IllegalArgumentException invalidConfiguration) {
            return failure("服务器配置无效：" + safeError(invalidConfiguration, settings));
        } catch (RuntimeException unexpected) {
            return failure("任务失败：" + safeError(unexpected, settings));
        }
    }

    private void startForeground(String operation) throws IOException {
        createNotificationChannel();
        Intent intent = new Intent(getApplicationContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                getApplicationContext(),
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String title = OP_RESTORE.equals(operation) ? "正在恢复私有云备份" : "正在备份到私有云";
        Notification notification = new Notification.Builder(getApplicationContext(), CHANNEL_ID)
                .setSmallIcon(OP_RESTORE.equals(operation)
                        ? android.R.drawable.stat_sys_download
                        : android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText("任务会在后台继续，可返回应用查看进度或取消")
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, true)
                .build();
        ForegroundInfo info = new ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        try {
            setForegroundAsync(info).get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new OperationCancelledException();
        } catch (ExecutionException unableToStart) {
            throw new IOException("无法启动后台传输通知", unableToStart.getCause());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager = (NotificationManager) getApplicationContext()
                .getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "备份与恢复", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示私有云文件传输进度");
        manager.createNotificationChannel(channel);
    }

    private CancellationToken cancellationToken() {
        return new CancellationToken() {
            @Override
            public boolean isCancellationRequested() {
                return isStopped();
            }
        };
    }

    private ProgressListener progressListener() {
        return new ProgressListener() {
            @Override
            public void onProgress(Progress progress) {
                Data data = new Data.Builder()
                        .putInt(KEY_PERCENT, progress.getPercent())
                        .putString(KEY_STAGE, progress.getStage().name())
                        .putString(KEY_DETAIL, truncate(progress.getCurrentPath(), 300))
                        .build();
                setProgressAsync(data);
            }
        };
    }

    private Result success(String message) {
        return Result.success(new Data.Builder().putString(KEY_MESSAGE, message).build());
    }

    private Result failure(String message) {
        return Result.failure(new Data.Builder()
                .putString(KEY_MESSAGE, truncate(message, 800))
                .build());
    }

    private static boolean isKnownOperation(String operation) {
        return OP_TEST.equals(operation)
                || OP_BACKUP.equals(operation)
                || OP_LIST.equals(operation)
                || OP_RESTORE.equals(operation);
    }

    private static String safeError(Throwable error, ConnectionSettings settings) {
        if (error instanceof RemoteStoreException) {
            int status = ((RemoteStoreException) error).getStatusCode();
            if (status == 401 || status == 403) return "服务器拒绝登录，请检查账号和密码";
            if (status == 507) return "服务器存储空间不足";
        }
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = error.getClass().getSimpleName();
        }
        if (settings != null) {
            message = redact(message, settings.getPassword());
            message = redact(message, settings.getUsername());
        }
        return truncate(message.replace('\r', ' ').replace('\n', ' ').trim(), 800);
    }

    private static String redact(String message, String secret) {
        return secret == null || secret.isEmpty() ? message : message.replace(secret, "***");
    }

    private static String truncate(String value, int maximum) {
        if (value == null) return "";
        return value.length() <= maximum ? value : value.substring(0, maximum) + "…";
    }
}
