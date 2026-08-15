package com.privatecloud.app.config;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores non-secret scheduling preferences; credentials remain in SettingsRepository. */
public final class ScheduleRepository {
    private static final String PREFS = "private_cloud_schedule_v1";
    private final SharedPreferences values;

    public ScheduleRepository(Context context) {
        values = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public BackupSchedule load() {
        return load("default-plan");
    }

    public BackupSchedule load(String planId) {
        String prefix = "plan." + planId + ".";
        if (!values.contains(prefix + "enabled") && "default-plan".equals(planId)
                && values.contains("enabled")) {
            BackupSchedule legacy = new BackupSchedule(
                    values.getBoolean("enabled", false),
                    clampInterval(values.getInt("interval_hours", 24)),
                    values.getBoolean("unmetered_only", true),
                    values.getBoolean("charging_only", false));
            save(planId, legacy);
            values.edit().remove("enabled").remove("interval_hours")
                    .remove("unmetered_only").remove("charging_only").commit();
            return legacy;
        }
        int interval = values.getInt(prefix + "interval_hours", 24);
        interval = clampInterval(interval);
        return new BackupSchedule(
                values.getBoolean(prefix + "enabled", false),
                interval,
                values.getBoolean(prefix + "unmetered_only", true),
                values.getBoolean(prefix + "charging_only", false));
    }

    public boolean save(BackupSchedule schedule) {
        return save("default-plan", schedule);
    }

    public boolean save(String planId, BackupSchedule schedule) {
        String prefix = "plan." + planId + ".";
        return values.edit()
                .putBoolean(prefix + "enabled", schedule.isEnabled())
                .putInt(prefix + "interval_hours", schedule.getIntervalHours())
                .putBoolean(prefix + "unmetered_only", schedule.isUnmeteredOnly())
                .putBoolean(prefix + "charging_only", schedule.isChargingOnly())
                .commit();
    }

    public boolean clear() { return values.edit().clear().commit(); }

    public boolean saveExclusions(String planId, String exclusions) {
        return values.edit().putString("exclusions." + planId, exclusions == null ? "" : exclusions)
                .commit();
    }

    public String loadExclusions(String planId) {
        return values.getString("exclusions." + planId, "");
    }

    public boolean saveRetentionCount(String planId, int count) {
        return values.edit().putInt("retention." + planId, count).commit();
    }

    public int loadRetentionCount(String planId) {
        int value = values.getInt("retention." + planId, 10);
        return value >= 1 && value <= 10_000 ? value : 10;
    }

    public void deletePlan(String planId) {
        String schedulePrefix = "plan." + planId + ".";
        SharedPreferences.Editor editor = values.edit()
                .remove("exclusions." + planId).remove("retention." + planId);
        for (String key : values.getAll().keySet()) {
            if (key.startsWith(schedulePrefix)) editor.remove(key);
        }
        editor.commit();
    }

    private static int clampInterval(int interval) {
        return interval >= 1 && interval <= 720 ? interval : 24;
    }
}
