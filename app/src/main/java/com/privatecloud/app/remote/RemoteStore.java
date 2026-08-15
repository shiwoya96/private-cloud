package com.privatecloud.app.remote;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/** Protocol-neutral operations used by backup and restore code. */
public interface RemoteStore extends Closeable {
    RemoteEntry stat(String remotePath) throws IOException;

    default boolean exists(String remotePath) throws IOException {
        try {
            stat(remotePath);
            return true;
        } catch (FileNotFoundException notFound) {
            return false;
        }
    }

    List<RemoteEntry> list(String remoteDirectory) throws IOException;

    /** Copies a complete remote file to {@code destination}; the destination is not closed. */
    void download(String remotePath, OutputStream destination) throws IOException;

    /**
     * Replaces or creates a remote file from {@code source}; the source is not closed.
     * {@code contentLength} may be -1 when it is unknown.
     */
    void upload(
            String remotePath,
            InputStream source,
            long contentLength,
            boolean overwrite) throws IOException;

    /** Creates exactly one directory. The parent must already exist. */
    void createDirectory(String remotePath) throws IOException;

    /** Creates the directory and any missing ancestors below the configured root. */
    void createDirectories(String remotePath) throws IOException;

    /** Deletes one file or directory tree below the configured root. */
    void delete(String remotePath) throws IOException;

    @Override
    void close() throws IOException;
}
