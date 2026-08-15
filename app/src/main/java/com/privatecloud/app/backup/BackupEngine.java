package com.privatecloud.app.backup;

import com.privatecloud.app.local.SafDocument;
import com.privatecloud.app.local.SafTree;
import com.privatecloud.app.model.ManifestEntry;
import com.privatecloud.app.model.SnapshotInfo;
import com.privatecloud.app.model.SnapshotManifest;
import com.privatecloud.app.remote.RemoteEntry;
import com.privatecloud.app.remote.RemoteStore;
import com.privatecloud.app.config.ExclusionRules;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Creates an immutable remote snapshot and publishes complete.json only after all bytes succeed. */
public final class BackupEngine {
    private final RemoteStore store;

    public BackupEngine(RemoteStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public SnapshotInfo backup(
            String planId,
            SafTree source,
            CancellationToken cancellationToken,
            ProgressListener progressListener) throws IOException {
        return backup(
                planId, source, new ExclusionRules(""), "", cancellationToken, progressListener);
    }

    public SnapshotInfo backup(
            String planId, SafTree source, ExclusionRules exclusions,
            CancellationToken cancellationToken, ProgressListener progressListener)
            throws IOException {
        return backup(planId, source, exclusions, "", cancellationToken, progressListener);
    }

    public SnapshotInfo backup(
            String planId, SafTree source, ExclusionRules exclusions, String recoveryKey,
            CancellationToken cancellationToken, ProgressListener progressListener)
            throws IOException {
        final String safePlanId = SnapshotManifest.requirePlanId(planId);
        final SafTree safeSource = Objects.requireNonNull(source, "source");
        final CancellationToken cancellation = cancellationToken == null
                ? CancellationToken.NONE : cancellationToken;
        final ProgressListener progress = progressListener == null
                ? ProgressListener.NONE : progressListener;
        final byte[] encryptionKey = recoveryKey == null || recoveryKey.isEmpty()
                ? null : RecoveryKeyCrypto.parseRecoveryKey(recoveryKey);

        safeSource.requirePersistedReadPermission();
        final SafDocument root = safeSource.getRootDocument();
        if (!root.isDirectory()) {
            throw new IOException("Selected backup source is not a directory");
        }
        progress.onProgress(progress(
                ProgressListener.Stage.SCANNING, 0L, -1L, 0L, -1L, null));
        List<SafDocument> scannedDocuments = safeSource.scan(
                cancellation,
                new SafTree.ScanObserver() {
                    @Override
                    public void onEntry(SafDocument entry, int discoveredEntries)
                            throws IOException {
                        cancellation.throwIfCancellationRequested();
                        progress.onProgress(progress(
                                ProgressListener.Stage.SCANNING,
                                discoveredEntries,
                                -1L,
                                0L,
                                -1L,
                                entry.displayPath()));
                    }
                });
        ArrayList<SafDocument> filteredDocuments = new ArrayList<SafDocument>();
        ExclusionRules safeExclusions = exclusions == null ? new ExclusionRules("") : exclusions;
        for (SafDocument document : scannedDocuments) {
            if (!safeExclusions.excludes(document.displayPath(), document.isDirectory())) {
                filteredDocuments.add(document);
            }
        }
        List<SafDocument> documents = filteredDocuments;
        safeSource.refreshRoot(root);

        long totalFiles = 0L;
        long knownTotalBytes = 0L;
        boolean allSizesKnown = true;
        for (SafDocument document : documents) {
            if (document.isDirectory()) {
                continue;
            }
            totalFiles++;
            if (document.getSize() < 0L) {
                allSizesKnown = false;
            } else {
                knownTotalBytes = checkedAdd(knownTotalBytes, document.getSize());
            }
        }
        final long totalBytesForProgress = allSizesKnown ? knownTotalBytes : -1L;

        cancellation.throwIfCancellationRequested();
        long createdAt = Math.max(1L, System.currentTimeMillis());
        String snapshotId = allocateSnapshotId(safePlanId, createdAt, cancellation);
        String objectsRoot = SnapshotLayout.objectsRoot(safePlanId, snapshotId);
        store.createDirectories(objectsRoot);

        ArrayList<ManifestEntry> entries = new ArrayList<ManifestEntry>(documents.size());
        long completedFiles = 0L;
        long completedBytes = 0L;
        for (SafDocument document : documents) {
            cancellation.throwIfCancellationRequested();
            if (document.isDirectory()) {
                try {
                    entries.add(ManifestEntry.directory(
                            document.getPathSegments(), document.getLastModified()));
                } catch (IllegalArgumentException invalid) {
                    throw new IOException(
                            "Unsupported source directory name: " + document.displayPath(), invalid);
                }
                continue;
            }

            final long bytesBeforeFile = completedBytes;
            final long filesBeforeFile = completedFiles;
            final String displayPath = document.displayPath();
            final long finalTotalFiles = totalFiles;
            TransferStreams.ByteObserver byteObserver = new TransferStreams.ByteObserver() {
                @Override
                public void onBytes(long fileBytes) {
                    progress.onProgress(progress(
                            ProgressListener.Stage.UPLOADING,
                            filesBeforeFile,
                            finalTotalFiles,
                            saturatingAdd(bytesBeforeFile, fileBytes),
                            totalBytesForProgress,
                            displayPath));
                }
            };

            String objectId = SnapshotLayout.newObjectId();
            TransferStreams.DigestingInputStream digesting =
                    new TransferStreams.DigestingInputStream(
                            safeSource.openForRead(document), cancellation, byteObserver);
            java.io.InputStream uploadInput = digesting;
            long uploadLength = document.getSize();
            if (encryptionKey != null) {
                uploadInput = new EncryptedObjectInputStream(digesting, encryptionKey, objectId);
                uploadLength = EncryptedObjectInputStream.encryptedLength(document.getSize());
            }
            try (java.io.InputStream input = uploadInput) {
                store.upload(
                        SnapshotLayout.objectPath(safePlanId, snapshotId, objectId),
                        input,
                        uploadLength,
                        false);
            }
            long actualSize = digesting.getCount();
            String sha256 = digesting.finishSha256();
            if (document.getSize() >= 0L && document.getSize() != actualSize) {
                throw new IOException("Source file size changed during backup: " + displayPath);
            }
            SafDocument refreshed = safeSource.refresh(document);
            if (metadataChanged(document, refreshed)) {
                throw new IOException("Source file changed during backup: " + displayPath);
            }
            RemoteEntry uploaded = store.stat(
                    SnapshotLayout.objectPath(safePlanId, snapshotId, objectId));
            long expectedRemoteSize = encryptionKey == null
                    ? actualSize : EncryptedObjectInputStream.encryptedLength(actualSize);
            if (uploaded.isDirectory()
                    || (uploaded.getSize() >= 0L
                            && uploaded.getSize() != expectedRemoteSize)) {
                throw new IOException(
                        "Remote store did not persist the complete object: " + displayPath);
            }

            try {
                entries.add(ManifestEntry.file(
                        document.getPathSegments(),
                        objectId,
                        actualSize,
                        document.getLastModified(),
                        safeMimeType(document.getMimeType()),
                        sha256));
            } catch (IllegalArgumentException invalid) {
                throw new IOException("Unsupported source file metadata: " + displayPath, invalid);
            }
            completedFiles++;
            completedBytes = checkedAdd(completedBytes, actualSize);
            progress.onProgress(progress(
                    ProgressListener.Stage.UPLOADING,
                    completedFiles,
                    totalFiles,
                    completedBytes,
                    totalBytesForProgress,
                    displayPath));
        }

        safeSource.refreshRoot(root);
        cancellation.throwIfCancellationRequested();
        final SnapshotManifest manifest;
        try {
            manifest = new SnapshotManifest(
                    safePlanId,
                    snapshotId,
                    createdAt,
                    safeSourceName(root.getDisplayName()),
                    entries,
                    encryptionKey == null ? "" : RecoveryKeyCrypto.fingerprint(recoveryKey));
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Unable to create a valid snapshot manifest", invalid);
        }
        byte[] manifestBytes = manifest.toJsonBytes();
        cancellation.throwIfCancellationRequested();
        SnapshotInfo expected = new SnapshotInfo(
                safePlanId,
                snapshotId,
                createdAt,
                manifest.getSourceName(),
                manifest.getFileCount(),
                manifest.getDirectoryCount(),
                manifest.getTotalBytes(),
                manifestBytes.length,
                SnapshotFormat.sha256(manifestBytes));

        progress.onProgress(progress(
                ProgressListener.Stage.COMMITTING,
                completedFiles,
                totalFiles,
                completedBytes,
                totalBytesForProgress,
                SnapshotLayout.MANIFEST_NAME));
        SnapshotFormat.uploadNewBytes(
                store,
                SnapshotLayout.manifestPath(safePlanId, snapshotId),
                manifestBytes,
                cancellation);
        cancellation.throwIfCancellationRequested();
        byte[] completeBytes = SnapshotFormat.encodeComplete(expected);
        SnapshotFormat.uploadNewBytes(
                store,
                SnapshotLayout.completePath(safePlanId, snapshotId),
                completeBytes,
                CancellationToken.NONE);

        // The marker is authoritative. Once it is uploaded, finish verification even when a
        // cancellation races with this tiny commit so a committed snapshot is reported as such.
        SnapshotRepository.CommittedSnapshot verified = new SnapshotRepository(store)
                .loadCommitted(safePlanId, snapshotId, CancellationToken.NONE);
        if (!expected.equals(verified.info)) {
            throw new InvalidSnapshotException("Committed snapshot summary changed after upload");
        }
        progress.onProgress(progress(
                ProgressListener.Stage.FINISHED,
                totalFiles,
                totalFiles,
                completedBytes,
                manifest.getTotalBytes(),
                snapshotId));
        return verified.info;
    }

    public SnapshotInfo backup(String planId, SafTree source) throws IOException {
        return backup(planId, source, CancellationToken.NONE, ProgressListener.NONE);
    }

    private String allocateSnapshotId(
            String planId, long createdAt, CancellationToken cancellation) throws IOException {
        for (int attempt = 0; attempt < 4; attempt++) {
            cancellation.throwIfCancellationRequested();
            String snapshotId = SnapshotLayout.newSnapshotId(createdAt);
            if (!store.exists(SnapshotLayout.snapshotRoot(planId, snapshotId))) {
                return snapshotId;
            }
        }
        throw new IOException("Unable to allocate a unique remote snapshot id");
    }

    private static boolean metadataChanged(SafDocument before, SafDocument after) {
        if (before.getSize() >= 0L
                && after.getSize() >= 0L
                && before.getSize() != after.getSize()) {
            return true;
        }
        return before.getLastModified() > 0L
                && after.getLastModified() > 0L
                && before.getLastModified() != after.getLastModified();
    }

    private static String safeMimeType(String value) {
        return ManifestEntry.isSafeFileMimeType(value) ? value : null;
    }

    private static String safeSourceName(String value) {
        if (value == null || value.isEmpty()) {
            return "Selected folder";
        }
        StringBuilder safe = new StringBuilder();
        for (int offset = 0; offset < value.length() && safe.length() < 240;) {
            int codePoint = value.codePointAt(offset);
            if (codePoint == 0
                    || codePoint == 0x7f
                    || codePoint < 0x20
                    || Character.getType(codePoint) == Character.SURROGATE) {
                safe.append('_');
            } else {
                safe.appendCodePoint(codePoint);
            }
            offset += Character.charCount(codePoint);
        }
        while (safe.toString().getBytes(StandardCharsets.UTF_8).length > 1024) {
            safe.setLength(safe.length() - 1);
        }
        return safe.length() == 0 ? "Selected folder" : safe.toString();
    }

    private static long checkedAdd(long left, long right) throws IOException {
        if (right < 0L || left > Long.MAX_VALUE - right) {
            throw new IOException("Snapshot byte count overflow");
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
