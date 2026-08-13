package com.privatecloud.app.remote;

import java.util.Objects;

/** Immutable metadata for a file or directory below a configured remote root. */
public final class RemoteEntry {
    private final String path;
    private final String name;
    private final boolean directory;
    private final long size;
    private final long lastModified;
    private final String etag;

    public RemoteEntry(
            String path,
            String name,
            boolean directory,
            long size,
            long lastModified,
            String etag) {
        this.path = RemotePaths.normalize(path);
        this.name = Objects.requireNonNull(name, "name");
        if (!this.name.equals(RemotePaths.name(this.path))) {
            throw new IllegalArgumentException("name must match the final path segment");
        }
        this.directory = directory;
        this.size = directory ? 0L : Math.max(-1L, size);
        this.lastModified = Math.max(0L, lastModified);
        this.etag = etag;
    }

    /** A normalized path relative to the store's configured root. Root is an empty string. */
    public String getPath() {
        return path;
    }

    public String getName() {
        return name;
    }

    public boolean isDirectory() {
        return directory;
    }

    /** File size in bytes, or -1 when the server did not provide it. */
    public long getSize() {
        return size;
    }

    /** Unix epoch time in milliseconds, or 0 when unknown. */
    public long getLastModified() {
        return lastModified;
    }

    /** Protocol-specific entity tag, or null when unavailable. */
    public String getEtag() {
        return etag;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RemoteEntry)) {
            return false;
        }
        RemoteEntry that = (RemoteEntry) other;
        return directory == that.directory
                && size == that.size
                && lastModified == that.lastModified
                && path.equals(that.path)
                && name.equals(that.name)
                && Objects.equals(etag, that.etag);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path, name, directory, size, lastModified, etag);
    }

    @Override
    public String toString() {
        return "RemoteEntry{" + path + (directory ? "/" : "") + ", size=" + size + "}";
    }
}
