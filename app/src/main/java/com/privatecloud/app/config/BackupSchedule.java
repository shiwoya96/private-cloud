package com.privatecloud.app.config;

/** User-controlled policy for the unique periodic backup job. */
public final class BackupSchedule {
    private final boolean enabled;
    private final int intervalHours;
    private final boolean unmeteredOnly;
    private final boolean chargingOnly;

    public BackupSchedule(
            boolean enabled, int intervalHours, boolean unmeteredOnly, boolean chargingOnly) {
        if (intervalHours < 1 || intervalHours > 24 * 30) {
            throw new IllegalArgumentException("Backup interval must be between 1 and 720 hours");
        }
        this.enabled = enabled;
        this.intervalHours = intervalHours;
        this.unmeteredOnly = unmeteredOnly;
        this.chargingOnly = chargingOnly;
    }

    public boolean isEnabled() { return enabled; }
    public int getIntervalHours() { return intervalHours; }
    public boolean isUnmeteredOnly() { return unmeteredOnly; }
    public boolean isChargingOnly() { return chargingOnly; }

    public static BackupSchedule defaults() {
        return new BackupSchedule(false, 24, true, false);
    }
}
