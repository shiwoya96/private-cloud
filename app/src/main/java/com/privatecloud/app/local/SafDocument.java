package com.privatecloud.app.local;

import android.net.Uri;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable metadata for a document below a user-selected Storage Access Framework tree. */
public final class SafDocument {
    private final Uri uri;
    private final String documentId;
    private final List<String> pathSegments;
    private final String displayName;
    private final boolean directory;
    private final long size;
    private final long lastModified;
    private final String mimeType;
    private final int flags;

    SafDocument(
            Uri uri,
            String documentId,
            List<String> pathSegments,
            String displayName,
            boolean directory,
            long size,
            long lastModified,
            String mimeType,
            int flags) {
        this.uri = Objects.requireNonNull(uri, "uri");
        this.documentId = Objects.requireNonNull(documentId, "documentId");
        this.pathSegments = Collections.unmodifiableList(
                new ArrayList<String>(Objects.requireNonNull(pathSegments, "pathSegments")));
        this.displayName = Objects.requireNonNull(displayName, "displayName");
        this.directory = directory;
        this.size = directory ? 0L : Math.max(-1L, size);
        this.lastModified = Math.max(0L, lastModified);
        this.mimeType = mimeType;
        this.flags = flags;
    }

    public Uri getUri() {
        return uri;
    }

    public String getDocumentId() {
        return documentId;
    }

    /** Relative path segments below the selected tree. Root itself has an empty path. */
    public List<String> getPathSegments() {
        return pathSegments;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isDirectory() {
        return directory;
    }

    /** Declared file size, or -1 when the provider does not expose it. */
    public long getSize() {
        return size;
    }

    /** Unix epoch milliseconds, or 0 when unknown. */
    public long getLastModified() {
        return lastModified;
    }

    public String getMimeType() {
        return mimeType;
    }

    public int getFlags() {
        return flags;
    }

    public String displayPath() {
        StringBuilder value = new StringBuilder();
        for (String segment : pathSegments) {
            if (value.length() > 0) {
                value.append('/');
            }
            value.append(segment);
        }
        return value.toString();
    }

    @Override
    public String toString() {
        return "SafDocument{" + (directory ? "directory " : "file ") + displayPath() + "}";
    }
}
