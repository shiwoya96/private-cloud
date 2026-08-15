package com.privatecloud.app.config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BackupScheduleTest {
    @Test
    public void defaultsAreConservative() {
        BackupSchedule schedule = BackupSchedule.defaults();
        assertFalse(schedule.isEnabled());
        assertTrue(schedule.isUnmeteredOnly());
        assertFalse(schedule.isChargingOnly());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsZeroInterval() {
        new BackupSchedule(true, 0, true, false);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsExcessiveInterval() {
        new BackupSchedule(true, 721, true, false);
    }
}
