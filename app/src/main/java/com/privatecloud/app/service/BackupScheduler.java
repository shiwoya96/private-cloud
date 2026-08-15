package com.privatecloud.app.service;

import android.content.Context;
import android.net.Uri;

import androidx.work.Constraints;
import androidx.work.BackoffPolicy;
import androidx.work.Data;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.privatecloud.app.config.BackupSchedule;
import com.privatecloud.app.config.ConnectionSettings;
import com.privatecloud.app.config.SettingsRepository;

import java.security.GeneralSecurityException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Creates or removes the application's unique automatic-backup job. */
public final class BackupScheduler {
    public static final String UNIQUE_WORK_NAME = "private-cloud-periodic-backup";
    public static final String TAG = "private-cloud-periodic-backup";

    private BackupScheduler() {}

    public static void apply(
            Context context,
            BackupSchedule schedule,
            ConnectionSettings settings,
            Uri treeUri) throws GeneralSecurityException {
        apply(context, schedule, settings, treeUri, "");
    }

    public static void apply(
            Context context, BackupSchedule schedule, ConnectionSettings settings,
            Uri treeUri, String exclusions) throws GeneralSecurityException {
        apply(context, schedule, settings, treeUri, exclusions, "");
    }

    public static void apply(
            Context context, BackupSchedule schedule, ConnectionSettings settings,
            Uri treeUri, String exclusions, String recoveryKey) throws GeneralSecurityException {
        apply(context, schedule, settings, treeUri, exclusions, recoveryKey, 10);
    }

    public static void apply(
            Context context, BackupSchedule schedule, ConnectionSettings settings,
            Uri treeUri, String exclusions, String recoveryKey, int retentionCount)
            throws GeneralSecurityException {
        WorkManager manager = WorkManager.getInstance(context.getApplicationContext());
        // Remove the pre-multi-plan periodic name during upgrade; new jobs are plan-scoped.
        manager.cancelUniqueWork(UNIQUE_WORK_NAME);
        String uniqueName = UNIQUE_WORK_NAME + ":" + settings.getPlanId();
        if (!schedule.isEnabled()) {
            manager.cancelUniqueWork(uniqueName);
            return;
        }
        if (treeUri == null) throw new IllegalArgumentException("请选择手机目录后再启用自动备份");
        UUID id = UUID.randomUUID();
        String envelope = new SettingsRepository(context).prepareOperation(
                id.toString(), settings, treeUri, CloudWorker.OP_BACKUP, null, "", exclusions,
                recoveryKey, retentionCount);
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(schedule.isUnmeteredOnly()
                        ? NetworkType.UNMETERED : NetworkType.CONNECTED)
                .setRequiresCharging(schedule.isChargingOnly())
                .setRequiresBatteryNotLow(true)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                CloudWorker.class, schedule.getIntervalHours(), TimeUnit.HOURS)
                .setId(id)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(new Data.Builder()
                        .putString(CloudWorker.KEY_ENCRYPTED_SPEC, envelope).build())
                .addTag(CloudWorker.TAG)
                .addTag(TAG)
                .addTag(CloudWorker.TAG + ":" + CloudWorker.OP_BACKUP)
                .build();
        manager.enqueueUniquePeriodicWork(
                uniqueName, ExistingPeriodicWorkPolicy.UPDATE, request);
    }
}
