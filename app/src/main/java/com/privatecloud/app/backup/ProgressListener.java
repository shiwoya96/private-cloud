package com.privatecloud.app.backup;

/** Receives worker-thread progress updates. Callers must marshal UI updates to the main thread. */
public interface ProgressListener {
    ProgressListener NONE = new ProgressListener() {
        @Override
        public void onProgress(Progress progress) {}
    };

    enum Stage {
        SCANNING,
        UPLOADING,
        COMMITTING,
        LISTING,
        PREPARING_RESTORE,
        DOWNLOADING,
        FINISHED
    }

    void onProgress(Progress progress);

    final class Progress {
        private final Stage stage;
        private final long completedFiles;
        private final long totalFiles;
        private final long completedBytes;
        private final long totalBytes;
        private final String currentPath;

        public Progress(
                Stage stage,
                long completedFiles,
                long totalFiles,
                long completedBytes,
                long totalBytes,
                String currentPath) {
            if (stage == null) {
                throw new IllegalArgumentException("stage must not be null");
            }
            if (completedFiles < 0L
                    || totalFiles < -1L
                    || completedBytes < 0L
                    || totalBytes < -1L) {
                throw new IllegalArgumentException("Invalid progress counters");
            }
            this.stage = stage;
            this.completedFiles = completedFiles;
            this.totalFiles = totalFiles;
            this.completedBytes = completedBytes;
            this.totalBytes = totalBytes;
            this.currentPath = currentPath;
        }

        public Stage getStage() {
            return stage;
        }

        public long getCompletedFiles() {
            return completedFiles;
        }

        /** Total files, or -1 while scanning. */
        public long getTotalFiles() {
            return totalFiles;
        }

        public long getCompletedBytes() {
            return completedBytes;
        }

        /** Total bytes, or -1 when at least one source size is unknown. */
        public long getTotalBytes() {
            return totalBytes;
        }

        public String getCurrentPath() {
            return currentPath;
        }

        /** Returns 0..100, or -1 when the total is unknown. */
        public int getPercent() {
            if (totalBytes > 0L) {
                return safePercent(completedBytes, totalBytes);
            }
            if (totalBytes == 0L && totalFiles >= 0L) {
                if (totalFiles == 0L) {
                    return 100;
                }
                return safePercent(completedFiles, totalFiles);
            }
            return -1;
        }

        private static int safePercent(long completed, long total) {
            if (completed >= total) {
                return 100;
            }
            // Floating point is sufficient for UI progress and avoids overflowing value * 100.
            return (int) Math.min(100.0d, ((double) completed / (double) total) * 100.0d);
        }
    }
}
