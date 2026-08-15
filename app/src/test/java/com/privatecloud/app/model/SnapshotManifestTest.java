package com.privatecloud.app.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public final class SnapshotManifestTest {
    @Test
    public void unicodeManifestRoundTripsWithStableBytes() throws Exception {
        ManifestEntry directory = ManifestEntry.directory(
                Collections.singletonList("相册"), 12L);
        ManifestEntry file = ManifestEntry.file(
                Arrays.asList("相册", "夏天 🌊.jpg"),
                "12345678-1234-1234-1234-123456789abc",
                42L,
                34L,
                "image/jpeg",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        SnapshotManifest original = new SnapshotManifest(
                "12345678-abcd",
                "1770000000000-12345678-1234-1234-1234-123456789abc",
                1770000000000L,
                "手机照片",
                Arrays.asList(file, directory),
                "0123456789AB");

        byte[] encoded = original.toJsonBytes();
        SnapshotManifest decoded = SnapshotManifest.fromJsonBytes(encoded);

        assertEquals(original.getPlanId(), decoded.getPlanId());
        assertEquals(original.getEntries(), decoded.getEntries());
        assertEquals(original.getTotalBytes(), decoded.getTotalBytes());
        assertEquals("0123456789AB", decoded.getEncryptionFingerprint());
        assertEquals(new String(encoded, "UTF-8"), new String(decoded.toJsonBytes(), "UTF-8"));
        assertFalse(decoded.usesOriginalPaths());
    }

    @Test
    public void unencryptedManifestUsesOriginalPaths() throws Exception {
        ManifestEntry file = ManifestEntry.file(
                Collections.singletonList("照片.jpg"),
                "12345678-1234-1234-1234-123456789abc",
                42L,
                34L,
                "image/jpeg",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        SnapshotManifest manifest = new SnapshotManifest(
                "12345678-abcd",
                "1770000000000-12345678-1234-1234-1234-123456789abc",
                1770000000000L,
                "手机照片",
                Collections.singletonList(file));

        assertTrue(manifest.usesOriginalPaths());
        assertTrue(new String(manifest.toJsonBytes(), "UTF-8")
                .contains("\"storageLayout\":\"original-paths\""));
    }

    @Test
    public void schemaTwoManifestKeepsLegacyOpaqueObjects() throws Exception {
        String legacy = "{\"schema\":2,\"planId\":\"default-plan\","
                + "\"snapshotId\":\"snapshot-1\",\"createdAt\":1,"
                + "\"sourceName\":\"source\",\"encryptionFingerprint\":\"\","
                + "\"entries\":[]}";

        assertFalse(SnapshotManifest.fromJsonBytes(legacy.getBytes("UTF-8"))
                .usesOriginalPaths());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsFileWithoutDeclaredParentDirectory() {
        ManifestEntry file = ManifestEntry.file(
                Arrays.asList("missing", "file.txt"),
                "12345678-1234-1234-1234-123456789abc",
                0L,
                0L,
                "text/plain",
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        new SnapshotManifest(
                "12345678-abcd",
                "1770000000000-12345678-1234-1234-1234-123456789abc",
                1770000000000L,
                "source",
                Collections.singletonList(file));
    }

    @Test
    public void collisionKeyDoesNotConfuseSegmentBoundaries() {
        assertNotEquals(
                ManifestEntry.pathKey(Arrays.asList("a", "bc"), 2),
                ManifestEntry.pathKey(Arrays.asList("ab", "c"), 2));
    }

    @Test(expected = java.io.IOException.class)
    public void rejectsTrailingJsonData() throws Exception {
        SnapshotManifest.fromJsonBytes((
                "{\"schema\":1,\"planId\":\"default-plan\","
                        + "\"snapshotId\":\"snapshot-1\",\"createdAt\":1,"
                        + "\"sourceName\":\"source\",\"entries\":[]} trailing")
                .getBytes("UTF-8"));
    }
}
