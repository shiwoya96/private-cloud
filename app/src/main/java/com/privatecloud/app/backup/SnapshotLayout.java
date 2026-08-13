package com.privatecloud.app.backup;

import com.privatecloud.app.model.SnapshotManifest;
import java.util.Locale;
import java.util.UUID;

/** Application-owned remote namespace. No user filename is ever placed in these paths. */
final class SnapshotLayout {
    static final String APP_ROOT = ".private-cloud/v1";
    static final String MANIFEST_NAME = "manifest.json";
    static final String COMPLETE_NAME = "complete.json";

    private SnapshotLayout() {}

    static String snapshotsRoot(String planId) {
        return APP_ROOT + "/plans/" + SnapshotManifest.requirePlanId(planId) + "/snapshots";
    }

    static String snapshotRoot(String planId, String snapshotId) {
        return snapshotsRoot(planId) + "/" + SnapshotManifest.requireSnapshotId(snapshotId);
    }

    static String objectsRoot(String planId, String snapshotId) {
        return snapshotRoot(planId, snapshotId) + "/objects";
    }

    static String objectPath(String planId, String snapshotId, String objectId) {
        String normalized;
        try {
            normalized = UUID.fromString(objectId).toString().toLowerCase(Locale.US);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid snapshot object id", invalid);
        }
        if (!normalized.equals(objectId)) {
            throw new IllegalArgumentException("Snapshot object id is not canonical");
        }
        return objectsRoot(planId, snapshotId) + "/" + objectId + ".blob";
    }

    static String manifestPath(String planId, String snapshotId) {
        return snapshotRoot(planId, snapshotId) + "/" + MANIFEST_NAME;
    }

    static String completePath(String planId, String snapshotId) {
        return snapshotRoot(planId, snapshotId) + "/" + COMPLETE_NAME;
    }

    static String newSnapshotId(long createdAt) {
        return createdAt + "-" + UUID.randomUUID().toString().toLowerCase(Locale.US);
    }

    static String newObjectId() {
        return UUID.randomUUID().toString().toLowerCase(Locale.US);
    }
}
