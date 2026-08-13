package com.privatecloud.app.backup;

import android.net.Uri;
import com.privatecloud.app.model.SnapshotInfo;
import java.util.Objects;

/** Successful restore summary. The returned directory was newly created for this operation. */
public final class RestoreResult {
    private final SnapshotInfo snapshot;
    private final Uri restoredTreeUri;
    private final String directoryName;
    private final long restoredFiles;
    private final long restoredBytes;

    public RestoreResult(
            SnapshotInfo snapshot,
            Uri restoredTreeUri,
            String directoryName,
            long restoredFiles,
            long restoredBytes) {
        this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        this.restoredTreeUri = Objects.requireNonNull(restoredTreeUri, "restoredTreeUri");
        this.directoryName = Objects.requireNonNull(directoryName, "directoryName");
        if (restoredFiles < 0L || restoredBytes < 0L) {
            throw new IllegalArgumentException("Restore counters must be non-negative");
        }
        this.restoredFiles = restoredFiles;
        this.restoredBytes = restoredBytes;
    }

    public SnapshotInfo getSnapshot() {
        return snapshot;
    }

    public Uri getRestoredTreeUri() {
        return restoredTreeUri;
    }

    public String getDirectoryName() {
        return directoryName;
    }

    public long getRestoredFiles() {
        return restoredFiles;
    }

    public long getRestoredBytes() {
        return restoredBytes;
    }
}
