package com.privatecloud.app.backup;

import com.privatecloud.app.model.ManifestEntry;
import com.privatecloud.app.model.SnapshotManifest;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Application-owned remote namespace. No user filename is ever placed in these paths. */
final class SnapshotLayout {
    static final String APP_ROOT = ".private-cloud/v1";
    static final String MANIFEST_NAME = "manifest.json";
    static final String COMPLETE_NAME = "complete.json";
    static final String FILES_NAME = "files";

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

    static String filesRoot(String planId, String snapshotId) {
        return snapshotRoot(planId, snapshotId) + "/" + FILES_NAME;
    }

    /** Maps a manifest path to the directly browsable files tree used by new snapshots. */
    static String filePath(String planId, String snapshotId, List<String> pathSegments) {
        if (pathSegments == null || pathSegments.isEmpty()) {
            throw new IllegalArgumentException("Snapshot file path must not be empty");
        }
        StringBuilder path = new StringBuilder(filesRoot(planId, snapshotId));
        for (String segment : pathSegments) {
            ManifestEntry.validatePathSegment(segment);
            if (segment.indexOf('/') >= 0 || segment.indexOf('\\') >= 0) {
                throw new IllegalArgumentException(
                        "Snapshot file names cannot contain path separators");
            }
            path.append('/').append(segment);
        }
        return path.toString();
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
