package com.privatecloud.app.backup;

import com.privatecloud.app.model.SnapshotInfo;
import com.privatecloud.app.model.SnapshotManifest;
import com.privatecloud.app.remote.RemoteEntry;
import com.privatecloud.app.remote.RemoteStore;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Discovers snapshots and proves their commit marker and manifest agree before exposing them. */
public final class SnapshotRepository {
    private final RemoteStore store;

    public SnapshotRepository(RemoteStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public List<SnapshotInfo> listCommitted(
            String planId,
            CancellationToken cancellationToken,
            ProgressListener progressListener) throws IOException {
        final String safePlanId = SnapshotManifest.requirePlanId(planId);
        CancellationToken cancellation = nonNullCancellation(cancellationToken);
        ProgressListener progress = nonNullProgress(progressListener);
        cancellation.throwIfCancellationRequested();
        String snapshotsRoot = SnapshotLayout.snapshotsRoot(safePlanId);
        if (!store.exists(snapshotsRoot)) {
            return Collections.emptyList();
        }
        List<RemoteEntry> children = store.list(snapshotsRoot);
        if (children.size() > SnapshotManifest.MAX_ENTRIES) {
            throw new IOException("Remote snapshot directory contains too many entries");
        }
        ArrayList<RemoteEntry> candidates = new ArrayList<RemoteEntry>();
        for (RemoteEntry child : children) {
            if (!child.isDirectory()) {
                continue;
            }
            try {
                SnapshotManifest.requireSnapshotId(child.getName());
                candidates.add(child);
            } catch (IllegalArgumentException ignored) {
                // Foreign data below the application root is never interpreted as a snapshot.
            }
        }

        ArrayList<SnapshotInfo> snapshots = new ArrayList<SnapshotInfo>();
        int checked = 0;
        for (RemoteEntry candidate : candidates) {
            cancellation.throwIfCancellationRequested();
            try {
                snapshots.add(loadCommitted(
                        safePlanId, candidate.getName(), cancellation).info);
            } catch (FileNotFoundException incomplete) {
                // Missing complete.json or manifest.json means the snapshot was never committed.
            } catch (InvalidSnapshotException invalid) {
                // A partial complete.json or mismatching manifest is not a committed snapshot.
            }
            checked++;
            progress.onProgress(new ProgressListener.Progress(
                    ProgressListener.Stage.LISTING,
                    checked,
                    candidates.size(),
                    0L,
                    0L,
                    candidate.getName()));
        }
        Collections.sort(snapshots, NEWEST_FIRST);
        return Collections.unmodifiableList(snapshots);
    }

    public List<SnapshotInfo> listCommitted(String planId) throws IOException {
        return listCommitted(planId, CancellationToken.NONE, ProgressListener.NONE);
    }

    public SnapshotManifest loadManifest(
            String planId, String snapshotId, CancellationToken cancellationToken)
            throws IOException {
        return loadCommitted(
                SnapshotManifest.requirePlanId(planId),
                SnapshotManifest.requireSnapshotId(snapshotId),
                nonNullCancellation(cancellationToken)).manifest;
    }

    public SnapshotManifest loadManifest(String planId, String snapshotId) throws IOException {
        return loadManifest(planId, snapshotId, CancellationToken.NONE);
    }

    /** Deletes only a validated, committed snapshot owned by the selected plan. */
    public void deleteCommitted(
            String planId, String snapshotId, CancellationToken cancellation) throws IOException {
        String safePlanId = SnapshotManifest.requirePlanId(planId);
        String safeSnapshotId = SnapshotManifest.requireSnapshotId(snapshotId);
        CancellationToken safeCancellation = nonNullCancellation(cancellation);
        loadCommitted(safePlanId, safeSnapshotId, safeCancellation);
        safeCancellation.throwIfCancellationRequested();
        store.delete(SnapshotLayout.snapshotRoot(safePlanId, safeSnapshotId));
    }

    /** Keeps the newest {@code keepCount} committed snapshots and removes older validated ones. */
    public int prune(
            String planId, int keepCount, CancellationToken cancellation,
            ProgressListener progress) throws IOException {
        if (keepCount < 1 || keepCount > 10_000) {
            throw new IllegalArgumentException("keepCount must be in 1..10000");
        }
        List<SnapshotInfo> snapshots = listCommitted(planId, cancellation, progress);
        int deleted = 0;
        for (int index = keepCount; index < snapshots.size(); index++) {
            deleteCommitted(planId, snapshots.get(index).getSnapshotId(), cancellation);
            deleted++;
        }
        return deleted;
    }

    CommittedSnapshot loadCommitted(
            String planId, String snapshotId, CancellationToken cancellation) throws IOException {
        if (cancellation == null) {
            cancellation = CancellationToken.NONE;
        }
        cancellation.throwIfCancellationRequested();
        String completePath = SnapshotLayout.completePath(planId, snapshotId);
        RemoteEntry completeEntry = store.stat(completePath);
        if (completeEntry.isDirectory()
                || completeEntry.getSize() > SnapshotFormat.MAX_COMPLETE_BYTES) {
            throw new InvalidSnapshotException("Invalid snapshot complete marker metadata");
        }
        byte[] completeBytes = SnapshotFormat.downloadBytes(
                store, completePath, SnapshotFormat.MAX_COMPLETE_BYTES, cancellation);
        if (completeEntry.getSize() >= 0L
                && completeEntry.getSize() != completeBytes.length) {
            throw new InvalidSnapshotException(
                    "Snapshot complete marker changed while it was being read");
        }
        SnapshotInfo info = SnapshotFormat.parseComplete(completeBytes);
        if (!planId.equals(info.getPlanId()) || !snapshotId.equals(info.getSnapshotId())) {
            throw new InvalidSnapshotException("Commit marker identity does not match its path");
        }

        String manifestPath = SnapshotLayout.manifestPath(planId, snapshotId);
        RemoteEntry manifestEntry = store.stat(manifestPath);
        if (manifestEntry.isDirectory()
                || manifestEntry.getSize() > SnapshotManifest.MAX_JSON_BYTES
                || (manifestEntry.getSize() >= 0L
                        && manifestEntry.getSize() != info.getManifestSize())) {
            throw new InvalidSnapshotException("Manifest metadata does not match commit marker");
        }
        byte[] manifestBytes = SnapshotFormat.downloadBytes(
                store, manifestPath, (int) info.getManifestSize(), cancellation);
        cancellation.throwIfCancellationRequested();
        if (manifestBytes.length != info.getManifestSize()
                || !SnapshotFormat.sha256(manifestBytes).equals(info.getManifestSha256())) {
            throw new InvalidSnapshotException("Manifest failed size or SHA-256 validation");
        }
        cancellation.throwIfCancellationRequested();

        final SnapshotManifest manifest;
        try {
            manifest = SnapshotManifest.fromJsonBytes(manifestBytes);
        } catch (IOException invalid) {
            throw new InvalidSnapshotException("Invalid committed manifest", invalid);
        }
        cancellation.throwIfCancellationRequested();
        if (!info.getPlanId().equals(manifest.getPlanId())
                || !info.getSnapshotId().equals(manifest.getSnapshotId())
                || info.getCreatedAt() != manifest.getCreatedAt()
                || !info.getSourceName().equals(manifest.getSourceName())
                || info.getFileCount() != manifest.getFileCount()
                || info.getDirectoryCount() != manifest.getDirectoryCount()
                || info.getTotalBytes() != manifest.getTotalBytes()) {
            throw new InvalidSnapshotException("Manifest summary does not match commit marker");
        }
        return new CommittedSnapshot(info, manifest);
    }

    static final class CommittedSnapshot {
        final SnapshotInfo info;
        final SnapshotManifest manifest;

        CommittedSnapshot(SnapshotInfo info, SnapshotManifest manifest) {
            this.info = info;
            this.manifest = manifest;
        }
    }

    private static CancellationToken nonNullCancellation(CancellationToken value) {
        return value == null ? CancellationToken.NONE : value;
    }

    private static ProgressListener nonNullProgress(ProgressListener value) {
        return value == null ? ProgressListener.NONE : value;
    }

    private static final Comparator<SnapshotInfo> NEWEST_FIRST =
            new Comparator<SnapshotInfo>() {
                @Override
                public int compare(SnapshotInfo left, SnapshotInfo right) {
                    if (left.getCreatedAt() != right.getCreatedAt()) {
                        return left.getCreatedAt() > right.getCreatedAt() ? -1 : 1;
                    }
                    return right.getSnapshotId().compareTo(left.getSnapshotId());
                }
            };
}
