package com.privatecloud.app.model;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Validated summary of a remotely committed snapshot. */
public final class SnapshotInfo {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private final String planId;
    private final String snapshotId;
    private final long createdAt;
    private final String sourceName;
    private final long fileCount;
    private final long directoryCount;
    private final long totalBytes;
    private final long manifestSize;
    private final String manifestSha256;

    public SnapshotInfo(
            String planId,
            String snapshotId,
            long createdAt,
            String sourceName,
            long fileCount,
            long directoryCount,
            long totalBytes,
            long manifestSize,
            String manifestSha256) {
        this.planId = SnapshotManifest.requirePlanId(planId);
        this.snapshotId = SnapshotManifest.requireSnapshotId(snapshotId);
        if (createdAt <= 0L) {
            throw new IllegalArgumentException("createdAt must be positive");
        }
        String safeSourceName = SnapshotManifest.requireSourceName(sourceName);
        if (fileCount < 0L
                || directoryCount < 0L
                || fileCount > SnapshotManifest.MAX_ENTRIES
                || directoryCount > SnapshotManifest.MAX_ENTRIES
                || fileCount > SnapshotManifest.MAX_ENTRIES - directoryCount
                || totalBytes < 0L
                || manifestSize <= 0L
                || manifestSize > SnapshotManifest.MAX_JSON_BYTES) {
            throw new IllegalArgumentException("Invalid snapshot counts or sizes");
        }
        if (manifestSha256 == null
                || !SHA_256.matcher(manifestSha256.toLowerCase(Locale.US)).matches()) {
            throw new IllegalArgumentException("Invalid manifest SHA-256 value");
        }
        this.createdAt = createdAt;
        this.sourceName = safeSourceName;
        this.fileCount = fileCount;
        this.directoryCount = directoryCount;
        this.totalBytes = totalBytes;
        this.manifestSize = manifestSize;
        this.manifestSha256 = manifestSha256.toLowerCase(Locale.US);
    }

    public String getPlanId() {
        return planId;
    }

    public String getSnapshotId() {
        return snapshotId;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public String getSourceName() {
        return sourceName;
    }

    public long getFileCount() {
        return fileCount;
    }

    public long getDirectoryCount() {
        return directoryCount;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public long getManifestSize() {
        return manifestSize;
    }

    public String getManifestSha256() {
        return manifestSha256;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof SnapshotInfo)) {
            return false;
        }
        SnapshotInfo that = (SnapshotInfo) other;
        return createdAt == that.createdAt
                && fileCount == that.fileCount
                && directoryCount == that.directoryCount
                && totalBytes == that.totalBytes
                && manifestSize == that.manifestSize
                && planId.equals(that.planId)
                && snapshotId.equals(that.snapshotId)
                && sourceName.equals(that.sourceName)
                && manifestSha256.equals(that.manifestSha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                planId,
                snapshotId,
                createdAt,
                sourceName,
                fileCount,
                directoryCount,
                totalBytes,
                manifestSize,
                manifestSha256);
    }

    @Override
    public String toString() {
        return "SnapshotInfo{" + snapshotId + ", files=" + fileCount
                + ", bytes=" + totalBytes + "}";
    }
}
