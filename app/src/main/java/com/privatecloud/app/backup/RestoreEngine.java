package com.privatecloud.app.backup;

import com.privatecloud.app.local.SafDocument;
import com.privatecloud.app.local.SafTree;
import com.privatecloud.app.model.ManifestEntry;
import com.privatecloud.app.model.SnapshotManifest;
import com.privatecloud.app.remote.RemoteEntry;
import com.privatecloud.app.remote.RemoteStore;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Restores a validated committed snapshot into a newly created SAF subdirectory. */
public final class RestoreEngine {
    private final RemoteStore store;

    public RestoreEngine(RemoteStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public RestoreResult restore(
            String planId,
            String snapshotId,
            SafTree destination,
            CancellationToken cancellationToken,
            ProgressListener progressListener) throws IOException {
        String safePlanId = SnapshotManifest.requirePlanId(planId);
        String safeSnapshotId = SnapshotManifest.requireSnapshotId(snapshotId);
        SafTree safeDestination = Objects.requireNonNull(destination, "destination");
        final CancellationToken cancellation = cancellationToken == null
                ? CancellationToken.NONE : cancellationToken;
        final ProgressListener progress = progressListener == null
                ? ProgressListener.NONE : progressListener;

        safeDestination.requirePersistedWritePermission();
        cancellation.throwIfCancellationRequested();
        progress.onProgress(progress(
                ProgressListener.Stage.PREPARING_RESTORE,
                0L,
                -1L,
                0L,
                -1L,
                safeSnapshotId));
        SnapshotRepository.CommittedSnapshot committed = new SnapshotRepository(store)
                .loadCommitted(safePlanId, safeSnapshotId, cancellation);
        SnapshotManifest manifest = committed.manifest;

        cancellation.throwIfCancellationRequested();
        String restoreDirectoryName = "PrivateCloud-restore-" + safeSnapshotId;
        SafDocument restoreRoot = safeDestination.createUniqueDirectory(
                restoreDirectoryName, cancellation);
        Set<String> createdDocumentIds = new HashSet<String>();
        createdDocumentIds.add(restoreRoot.getDocumentId());
        Map<String, SafDocument> directories = new HashMap<String, SafDocument>();
        directories.put("", restoreRoot);

        for (ManifestEntry entry : manifest.getEntries()) {
            cancellation.throwIfCancellationRequested();
            if (!entry.isDirectory()) {
                continue;
            }
            SafDocument parent = directories.get(parentKey(entry.getPathSegments()));
            if (parent == null) {
                throw new InvalidSnapshotException(
                        "Manifest directory parent was not restored: " + entry.displayPath());
            }
            SafDocument created = safeDestination.createDirectoryExact(
                    parent, lastSegment(entry.getPathSegments()));
            if (!createdDocumentIds.add(created.getDocumentId())) {
                throw new IOException("Document provider reused a restore destination URI");
            }
            directories.put(entry.pathKey(), created);
            progress.onProgress(progress(
                    ProgressListener.Stage.PREPARING_RESTORE,
                    0L,
                    manifest.getFileCount(),
                    0L,
                    manifest.getTotalBytes(),
                    entry.displayPath()));
        }

        long completedFiles = 0L;
        long completedBytes = 0L;
        for (ManifestEntry entry : manifest.getEntries()) {
            if (entry.isDirectory()) {
                continue;
            }
            cancellation.throwIfCancellationRequested();
            SafDocument parent = directories.get(parentKey(entry.getPathSegments()));
            if (parent == null) {
                throw new InvalidSnapshotException(
                        "Manifest file parent was not restored: " + entry.displayPath());
            }

            String remoteObjectPath = SnapshotLayout.objectPath(
                    safePlanId, safeSnapshotId, entry.getObjectId());
            RemoteEntry remoteObject = store.stat(remoteObjectPath);
            if (remoteObject.isDirectory()
                    || (remoteObject.getSize() >= 0L
                            && remoteObject.getSize() != entry.getSize())) {
                throw new InvalidSnapshotException(
                        "Snapshot object metadata mismatch: " + entry.displayPath());
            }

            SafDocument target = safeDestination.createFileExact(
                    parent,
                    lastSegment(entry.getPathSegments()),
                    entry.getMimeType());
            if (!createdDocumentIds.add(target.getDocumentId())) {
                throw new IOException("Document provider reused a restore destination URI");
            }
            final long bytesBeforeFile = completedBytes;
            final long filesBeforeFile = completedFiles;
            final String displayPath = entry.displayPath();
            TransferStreams.ByteObserver observer = new TransferStreams.ByteObserver() {
                @Override
                public void onBytes(long fileBytes) {
                    progress.onProgress(progress(
                            ProgressListener.Stage.DOWNLOADING,
                            filesBeforeFile,
                            committed.info.getFileCount(),
                            saturatingAdd(bytesBeforeFile, fileBytes),
                            committed.info.getTotalBytes(),
                            displayPath));
                }
            };

            try {
                try (TransferStreams.VerifyingOutputStream verifying =
                        new TransferStreams.VerifyingOutputStream(
                                safeDestination.openNewFileForWrite(target),
                                entry.getSize(),
                                entry.getSha256(),
                                cancellation,
                                observer)) {
                    store.download(remoteObjectPath, verifying);
                    cancellation.throwIfCancellationRequested();
                    verifying.verify();
                    verifying.flush();
                }
                SafDocument persisted = safeDestination.refresh(target);
                if (persisted.getSize() >= 0L && persisted.getSize() != entry.getSize()) {
                    throw new InvalidSnapshotException(
                            "Destination provider stored an unexpected size: "
                                    + entry.displayPath());
                }
                verifyPersistedFile(safeDestination, target, entry, cancellation);
            } catch (IOException failure) {
                cleanupFailedFile(safeDestination, target, failure);
                throw failure;
            } catch (RuntimeException failure) {
                cleanupFailedFile(safeDestination, target, failure);
                throw failure;
            }

            completedFiles++;
            completedBytes = checkedAdd(completedBytes, entry.getSize());
            progress.onProgress(progress(
                    ProgressListener.Stage.DOWNLOADING,
                    completedFiles,
                    committed.info.getFileCount(),
                    completedBytes,
                    committed.info.getTotalBytes(),
                    displayPath));
        }

        progress.onProgress(progress(
                ProgressListener.Stage.FINISHED,
                committed.info.getFileCount(),
                committed.info.getFileCount(),
                committed.info.getTotalBytes(),
                committed.info.getTotalBytes(),
                restoreRoot.getDisplayName()));
        return new RestoreResult(
                committed.info,
                restoreRoot.getUri(),
                restoreRoot.getDisplayName(),
                completedFiles,
                completedBytes);
    }

    public RestoreResult restore(
            String planId, String snapshotId, SafTree destination) throws IOException {
        return restore(
                planId,
                snapshotId,
                destination,
                CancellationToken.NONE,
                ProgressListener.NONE);
    }

    private static void cleanupFailedFile(
            SafTree destination, SafDocument target, Throwable failure) {
        try {
            destination.deleteCreatedDocument(target);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static void verifyPersistedFile(
            SafTree destination,
            SafDocument target,
            ManifestEntry expected,
            CancellationToken cancellation) throws IOException {
        MessageDigest digest = SnapshotFormat.newSha256();
        byte[] buffer = new byte[64 * 1024];
        long count = 0L;
        try (InputStream input = destination.openForRead(target)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                cancellation.throwIfCancellationRequested();
                if (read > expected.getSize() - count) {
                    throw new InvalidSnapshotException(
                            "Restored file exceeded its declared size: "
                                    + expected.displayPath());
                }
                digest.update(buffer, 0, read);
                count += read;
            }
        }
        if (count != expected.getSize()
                || !SnapshotFormat.hex(digest.digest()).equals(expected.getSha256())) {
            throw new InvalidSnapshotException(
                    "Restored file failed persisted SHA-256 validation: "
                            + expected.displayPath());
        }
    }

    private static String parentKey(List<String> path) {
        return ManifestEntry.pathKey(path, path.size() - 1);
    }

    private static String lastSegment(List<String> path) {
        return path.get(path.size() - 1);
    }

    private static long checkedAdd(long left, long right) throws IOException {
        if (right < 0L || left > Long.MAX_VALUE - right) {
            throw new IOException("Restored byte count overflow");
        }
        return left + right;
    }

    private static long saturatingAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static ProgressListener.Progress progress(
            ProgressListener.Stage stage,
            long completedFiles,
            long totalFiles,
            long completedBytes,
            long totalBytes,
            String currentPath) {
        return new ProgressListener.Progress(
                stage,
                completedFiles,
                totalFiles,
                completedBytes,
                totalBytes,
                currentPath);
    }
}
