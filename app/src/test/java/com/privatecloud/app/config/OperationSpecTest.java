package com.privatecloud.app.config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;
import com.privatecloud.app.backup.RecoveryKeyCrypto;

public final class OperationSpecTest {
    @Test
    public void roundTripsEveryImmutableOperationField() {
        ConnectionSettings settings = new ConnectionSettings(
                ConnectionSettings.Protocol.SMB,
                "",
                "nas.lan",
                1445,
                "手机备份",
                "WORKGROUP",
                "alice",
                "p@ss\"word",
                "pixel/photos",
                "12345678-abcd");
        String recoveryKey = RecoveryKeyCrypto.generateRecoveryKey();
        OperationSpec original = new OperationSpec(
                "restore",
                "20260813T101112Z-deadbeef",
                settings,
                "content://com.example/tree/primary%3APictures",
                "Camera/important", "*.tmp\nCache", recoveryKey, 17);

        OperationSpec decoded = OperationSpec.fromJson(original.toJson());

        assertEquals("restore", decoded.getOperation());
        assertEquals("20260813T101112Z-deadbeef", decoded.getSnapshotId());
        assertEquals("content://com.example/tree/primary%3APictures", decoded.getTreeUri());
        assertEquals(ConnectionSettings.Protocol.SMB, decoded.getSettings().getProtocol());
        assertEquals("nas.lan", decoded.getSettings().getSmbHost());
        assertEquals(1445, decoded.getSettings().getSmbPort());
        assertEquals("手机备份", decoded.getSettings().getSmbShare());
        assertEquals("p@ss\"word", decoded.getSettings().getPassword());
        assertEquals("pixel/photos", decoded.getSettings().getRemotePath());
        assertEquals("Camera/important", decoded.getSelectionPath());
        assertEquals("*.tmp\nCache", decoded.getExclusions());
        assertEquals(recoveryKey, decoded.getRecoveryKey());
        assertEquals(17, decoded.getRetentionCount());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMalformedOrTamperedPlaintextAfterAuthentication() {
        OperationSpec.fromJson("{\"v\":1,\"operation\":\"backup\"}");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTrailingJsonValue() {
        OperationSpec.fromJson(validBackupSpec().toJson() + " true");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsSnapshotIdOnNonRestoreOperation() {
        OperationSpec spec = new OperationSpec(
                "backup",
                "unexpected-snapshot",
                webDavSettings(),
                "content://com.example/tree/root");
        OperationSpec.validateSemantics(spec);
    }

    @Test
    public void aadBindsEnvelopeToExactWorkRequestId() {
        String first = WorkEnvelopePolicy.aad("01234567-89ab-cdef-0123-456789abcdef");
        String second = WorkEnvelopePolicy.aad("11234567-89ab-cdef-0123-456789abcdef");

        assertNotEquals(first, second);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEnvelopeAboveConservativeWorkManagerBudget() {
        StringBuilder oversized = new StringBuilder(WorkEnvelopePolicy.MAX_ENVELOPE_BYTES + 1);
        for (int index = 0; index <= WorkEnvelopePolicy.MAX_ENVELOPE_BYTES; index++) {
            oversized.append('a');
        }
        WorkEnvelopePolicy.requireFits(oversized.toString());
    }

    private static ConnectionSettings webDavSettings() {
        return new ConnectionSettings(
                ConnectionSettings.Protocol.WEBDAV,
                "https://example.test/dav/",
                "",
                445,
                "",
                "",
                "alice",
                "secret",
                "phone",
                "12345678-abcd");
    }

    private static OperationSpec validBackupSpec() {
        return new OperationSpec(
                "backup", "", webDavSettings(), "content://com.example/tree/root");
    }
}
