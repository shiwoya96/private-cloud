package com.privatecloud.app.backup;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import org.junit.Test;

public final class SnapshotLayoutTest {
    private static final String PLAN = "12345678-abcd";
    private static final String SNAPSHOT =
            "1770000000000-12345678-1234-1234-1234-123456789abc";

    @Test
    public void preservesOriginalUnicodePathInBrowsableTree() {
        assertEquals(
                SnapshotLayout.filesRoot(PLAN, SNAPSHOT) + "/相册/夏天 🌊.jpg",
                SnapshotLayout.filePath(
                        PLAN, SNAPSHOT, Arrays.asList("相册", "夏天 🌊.jpg")));
    }

    @Test
    public void legacyObjectPathRemainsAvailable() {
        assertEquals(
                SnapshotLayout.objectsRoot(PLAN, SNAPSHOT)
                        + "/12345678-1234-1234-1234-123456789abc.blob",
                SnapshotLayout.objectPath(
                        PLAN, SNAPSHOT, "12345678-1234-1234-1234-123456789abc"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmbeddedPathSeparator() {
        SnapshotLayout.filePath(PLAN, SNAPSHOT, Arrays.asList("相册", "bad/name.jpg"));
    }
}
