package com.privatecloud.app.local;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import com.privatecloud.app.backup.CancellationToken;
import com.privatecloud.app.model.ManifestEntry;
import com.privatecloud.app.model.SnapshotManifest;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Safe, path-free access to one user-selected Storage Access Framework directory tree. */
public final class SafTree {
    public static final int MAX_ENTRIES = SnapshotManifest.MAX_ENTRIES;
    private static final int MAX_DOCUMENT_ID_CHARS = 16 * 1024;

    private static final String[] PROJECTION = new String[] {
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS
    };

    private final ContentResolver resolver;
    private final Uri treeUri;
    private final String authority;
    private final String rootDocumentId;
    private final Uri rootDocumentUri;
    private final Set<String> createdDocumentIds =
            Collections.synchronizedSet(new HashSet<String>());
    private final Set<String> openedForWriteDocumentIds =
            Collections.synchronizedSet(new HashSet<String>());

    public SafTree(Context context, Uri treeUri) {
        this(Objects.requireNonNull(context, "context").getContentResolver(), treeUri);
    }

    public SafTree(ContentResolver resolver, Uri treeUri) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.treeUri = Objects.requireNonNull(treeUri, "treeUri");
        if (!"content".equals(treeUri.getScheme())
                || !DocumentsContract.isTreeUri(treeUri)) {
            throw new IllegalArgumentException("URI is not a Storage Access Framework tree URI");
        }
        authority = treeUri.getAuthority();
        if (authority == null || authority.isEmpty()) {
            throw new IllegalArgumentException("Tree URI has no document-provider authority");
        }
        try {
            rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
            validateDocumentId(rootDocumentId);
            rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
                    treeUri, rootDocumentId);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid Storage Access Framework tree URI", invalid);
        }
    }

    /** Intent for selecting a directory whose grant can be reused by background work. */
    public static Intent newOpenDocumentTreeIntent(Uri initialUri) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (initialUri != null) {
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri);
        }
        return intent;
    }

    /** Persists exactly the read/write flags actually returned by the system picker. */
    public static int persistPickerPermission(
            ContentResolver resolver, Uri treeUri, int resultIntentFlags) throws IOException {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(treeUri, "treeUri");
        int takeFlags = resultIntentFlags
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if ((takeFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            throw new IOException("The selected directory did not grant read access");
        }
        try {
            resolver.takePersistableUriPermission(treeUri, takeFlags);
            return takeFlags;
        } catch (SecurityException denied) {
            throw new IOException("Unable to persist access to the selected directory", denied);
        }
    }

    public Uri getTreeUri() {
        return treeUri;
    }

    public SafDocument getRootDocument() throws IOException {
        return queryDocument(rootDocumentUri, Collections.<String>emptyList());
    }

    public void requirePersistedReadPermission() throws IOException {
        requirePersistedPermission(false);
    }

    public void requirePersistedWritePermission() throws IOException {
        requirePersistedPermission(true);
    }

    /** Iteratively scans descendants. Duplicate document IDs are rejected instead of skipped. */
    public List<SafDocument> scan(
            CancellationToken cancellationToken, ScanObserver observer) throws IOException {
        CancellationToken cancellation = nonNullCancellation(cancellationToken);
        ScanObserver safeObserver = observer == null ? ScanObserver.NONE : observer;
        requirePersistedReadPermission();
        cancellation.throwIfCancellationRequested();

        SafDocument root = getRootDocument();
        if (!root.isDirectory()) {
            throw new IOException("Selected SAF tree root is not a directory");
        }
        ArrayDeque<SafDocument> pendingDirectories = new ArrayDeque<SafDocument>();
        pendingDirectories.add(root);
        Set<String> visitedDocumentIds = new HashSet<String>();
        visitedDocumentIds.add(rootDocumentId);
        ArrayList<SafDocument> result = new ArrayList<SafDocument>();

        while (!pendingDirectories.isEmpty()) {
            cancellation.throwIfCancellationRequested();
            SafDocument parent = pendingDirectories.removeFirst();
            List<SafDocument> children = queryChildren(parent);
            for (SafDocument child : children) {
                cancellation.throwIfCancellationRequested();
                if (!visitedDocumentIds.add(child.getDocumentId())) {
                    throw new IOException(
                            "Document provider returned a cycle or duplicate document id");
                }
                if (result.size() >= MAX_ENTRIES) {
                    throw new IOException("Selected tree exceeds " + MAX_ENTRIES + " entries");
                }
                result.add(child);
                safeObserver.onEntry(child, result.size());
                if (child.isDirectory()) {
                    pendingDirectories.addLast(child);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    public List<SafDocument> scan(CancellationToken cancellationToken) throws IOException {
        return scan(cancellationToken, ScanObserver.NONE);
    }

    public InputStream openForRead(SafDocument document) throws IOException {
        requireBelongsToTree(document);
        if (document.isDirectory()) {
            throw new IOException("Cannot read a directory as a file");
        }
        if ((document.getFlags() & Document.FLAG_VIRTUAL_DOCUMENT) != 0) {
            throw new IOException(
                    "Virtual SAF documents are not supported: " + document.displayPath());
        }
        try {
            InputStream input = resolver.openInputStream(document.getUri());
            if (input == null) {
                throw new FileNotFoundException(
                        "Document provider returned no input stream: " + document.displayPath());
            }
            return input;
        } catch (SecurityException denied) {
            throw new IOException("Read access to the selected directory was revoked", denied);
        }
    }

    /** Re-queries a scanned document so callers can detect mutation during an upload. */
    public SafDocument refresh(SafDocument document) throws IOException {
        requireBelongsToTree(document);
        SafDocument refreshed = queryDocument(document.getUri(), document.getPathSegments());
        if (!refreshed.getDocumentId().equals(document.getDocumentId())
                || !refreshed.getDisplayName().equals(document.getDisplayName())
                || refreshed.isDirectory() != document.isDirectory()) {
            throw new IOException("Source document changed identity during backup");
        }
        return refreshed;
    }

    /** Creates a directory that did not exist when this method started. */
    public SafDocument createUniqueDirectory(
            String baseName, CancellationToken cancellationToken) throws IOException {
        CancellationToken cancellation = nonNullCancellation(cancellationToken);
        requirePersistedWritePermission();
        ManifestEntry.validatePathSegment(baseName);
        SafDocument root = getRootDocument();
        for (int suffix = 0; suffix < 10_000; suffix++) {
            cancellation.throwIfCancellationRequested();
            String candidate = suffix == 0 ? baseName : baseName + "-" + suffix;
            try {
                ManifestEntry.validatePathSegment(candidate);
            } catch (IllegalArgumentException invalidCandidate) {
                throw new IOException("Restore directory name is too long", invalidCandidate);
            }
            List<SafDocument> children = queryChildren(root);
            if (findByExactName(children, candidate) != null) {
                continue;
            }
            return createExact(root, candidate, Document.MIME_TYPE_DIR, true, children);
        }
        throw new IOException("Unable to allocate a unique restore directory name");
    }

    public SafDocument createDirectoryExact(SafDocument parent, String displayName)
            throws IOException {
        requirePersistedWritePermission();
        requireDirectory(parent);
        ManifestEntry.validatePathSegment(displayName);
        List<SafDocument> existing = queryChildren(parent);
        if (findByExactName(existing, displayName) != null) {
            throw new IOException("Refusing to overwrite existing directory: " + displayName);
        }
        return createExact(parent, displayName, Document.MIME_TYPE_DIR, true, existing);
    }

    public SafDocument createFileExact(
            SafDocument parent, String displayName, String mimeType) throws IOException {
        requirePersistedWritePermission();
        requireDirectory(parent);
        ManifestEntry.validatePathSegment(displayName);
        String safeMimeType = ManifestEntry.isSafeFileMimeType(mimeType)
                ? mimeType : "application/octet-stream";
        List<SafDocument> existing = queryChildren(parent);
        if (findByExactName(existing, displayName) != null) {
            throw new IOException("Refusing to overwrite existing file: " + displayName);
        }
        return createExact(parent, displayName, safeMimeType, false, existing);
    }

    /** Opens a newly created file. The fallback mode is safe because the document is new. */
    public OutputStream openNewFileForWrite(SafDocument document) throws IOException {
        requireBelongsToTree(document);
        if (document.isDirectory()) {
            throw new IOException("Cannot write file bytes to a directory");
        }
        if (!createdDocumentIds.contains(document.getDocumentId())) {
            throw new IOException("Refusing to open a pre-existing SAF document for writing");
        }
        if (!openedForWriteDocumentIds.add(document.getDocumentId())) {
            throw new IOException("Refusing to reopen a restore file for writing");
        }
        try {
            OutputStream output;
            try {
                output = resolver.openOutputStream(document.getUri(), "wt");
            } catch (FileNotFoundException | IllegalArgumentException unsupportedTruncateMode) {
                try {
                    output = resolver.openOutputStream(document.getUri(), "w");
                } catch (IllegalArgumentException invalidMode) {
                    throw new IOException("Document provider rejected write mode", invalidMode);
                }
            }
            if (output == null) {
                throw new FileNotFoundException(
                        "Document provider returned no output stream: " + document.displayPath());
            }
            return output;
        } catch (SecurityException denied) {
            openedForWriteDocumentIds.remove(document.getDocumentId());
            throw new IOException("Write access to the selected directory was revoked", denied);
        } catch (IllegalArgumentException invalidMode) {
            openedForWriteDocumentIds.remove(document.getDocumentId());
            throw new IOException("Document provider rejected write mode", invalidMode);
        } catch (IOException failure) {
            openedForWriteDocumentIds.remove(document.getDocumentId());
            throw failure;
        }
    }

    /** Deletes a document known to have been created by the current restore operation. */
    public boolean deleteCreatedDocument(SafDocument document) throws IOException {
        requireBelongsToTree(document);
        if (!createdDocumentIds.contains(document.getDocumentId())) {
            throw new IOException("Refusing to delete a pre-existing SAF document");
        }
        try {
            boolean deleted = DocumentsContract.deleteDocument(resolver, document.getUri());
            if (deleted) {
                createdDocumentIds.remove(document.getDocumentId());
                openedForWriteDocumentIds.remove(document.getDocumentId());
            }
            return deleted;
        } catch (FileNotFoundException alreadyGone) {
            createdDocumentIds.remove(document.getDocumentId());
            openedForWriteDocumentIds.remove(document.getDocumentId());
            return false;
        } catch (SecurityException denied) {
            throw new IOException("Unable to clean up a newly created SAF document", denied);
        }
    }

    private void requirePersistedPermission(boolean write) throws IOException {
        try {
            for (UriPermission permission : resolver.getPersistedUriPermissions()) {
                if (treeUri.equals(permission.getUri())
                        && permission.isReadPermission()
                        && (!write || permission.isWritePermission())) {
                    return;
                }
            }
        } catch (SecurityException denied) {
            throw new IOException("Unable to inspect persisted directory permission", denied);
        }
        throw new IOException(write
                ? "Persisted read/write access to the selected directory is missing"
                : "Persisted read access to the selected directory is missing");
    }

    private SafDocument createExact(
            SafDocument parent,
            String displayName,
            String mimeType,
            boolean directory,
            List<SafDocument> existingChildren) throws IOException {
        Set<String> oldDocumentIds = new HashSet<String>();
        for (SafDocument child : existingChildren) {
            oldDocumentIds.add(child.getDocumentId());
        }

        final Uri createdUri;
        try {
            createdUri = DocumentsContract.createDocument(
                    resolver, parent.getUri(), mimeType, displayName);
        } catch (SecurityException denied) {
            throw new IOException("Write access to the selected directory was revoked", denied);
        }
        if (createdUri == null) {
            throw new IOException("Document provider refused to create: " + displayName);
        }
        // Never query, write, or delete a URI that a broken provider returned outside the
        // user-granted tree. In particular, this check happens before best-effort cleanup.
        requireUriBelongsToTree(createdUri);

        ArrayList<String> path = new ArrayList<String>(parent.getPathSegments());
        path.add(displayName);
        final SafDocument created;
        try {
            created = queryDocument(createdUri, path);
        } catch (IOException failure) {
            bestEffortDelete(createdUri);
            throw failure;
        } catch (RuntimeException failure) {
            bestEffortDelete(createdUri);
            throw failure;
        }
        boolean valid = !oldDocumentIds.contains(created.getDocumentId())
                && created.isDirectory() == directory
                && displayName.equals(created.getDisplayName());
        if (!valid) {
            if (!oldDocumentIds.contains(created.getDocumentId())) {
                bestEffortDelete(createdUri);
            }
            throw new IOException(
                    "Document provider reused or renamed the requested destination: "
                            + displayName);
        }
        try {
            List<SafDocument> afterCreate = queryChildren(parent);
            Set<String> afterDocumentIds = new HashSet<String>();
            int exactNameMatches = 0;
            for (SafDocument child : afterCreate) {
                afterDocumentIds.add(child.getDocumentId());
                if (displayName.equals(child.getDisplayName())) {
                    exactNameMatches++;
                    if (!created.getDocumentId().equals(child.getDocumentId())) {
                        throw new IOException(
                                "Destination name became ambiguous during creation: "
                                        + displayName);
                    }
                }
            }
            if (!afterDocumentIds.containsAll(oldDocumentIds)
                    || !afterDocumentIds.contains(created.getDocumentId())
                    || exactNameMatches != 1) {
                throw new IOException(
                        "Document provider did not preserve existing children while creating: "
                                + displayName);
            }
        } catch (IOException failure) {
            bestEffortDelete(createdUri);
            throw failure;
        } catch (RuntimeException failure) {
            bestEffortDelete(createdUri);
            throw failure;
        }
        if (!createdDocumentIds.add(created.getDocumentId())) {
            throw new IOException("Document provider reused a document created by this operation");
        }
        return created;
    }

    private void bestEffortDelete(Uri createdUri) {
        try {
            DocumentsContract.deleteDocument(resolver, createdUri);
        } catch (Exception ignored) {
            // Only newly-created URIs reach this helper; preserve the original failure.
        }
    }

    private List<SafDocument> queryChildren(SafDocument parent) throws IOException {
        requireDirectory(parent);
        Uri childrenUri;
        try {
            childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, parent.getDocumentId());
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid child document URI returned by provider", invalid);
        }
        ArrayList<SafDocument> children = new ArrayList<SafDocument>();
        Set<String> childDocumentIds = new HashSet<String>();
        Set<String> childDisplayNames = new HashSet<String>();
        try (Cursor cursor = resolver.query(childrenUri, PROJECTION, null, null, null)) {
            if (cursor == null) {
                throw new IOException("Document provider returned no directory cursor");
            }
            ColumnIndexes columns = new ColumnIndexes(cursor);
            while (cursor.moveToNext()) {
                if (children.size() >= MAX_ENTRIES) {
                    throw new IOException(
                            "A SAF directory exceeds the " + MAX_ENTRIES + " entry limit");
                }
                String documentId = requiredColumn(cursor, columns.documentId, "document id");
                validateDocumentId(documentId);
                if (!childDocumentIds.add(documentId)) {
                    throw new IOException(
                            "Document provider returned a duplicate child document id");
                }
                String displayName = requiredColumn(cursor, columns.displayName, "display name");
                try {
                    ManifestEntry.validatePathSegment(displayName);
                } catch (IllegalArgumentException unsafeName) {
                    throw new IOException(
                            "Document provider returned an unsafe name", unsafeName);
                }
                if (!childDisplayNames.add(displayName)) {
                    throw new IOException(
                            "Document provider returned duplicate child display names");
                }
                ArrayList<String> path = new ArrayList<String>(parent.getPathSegments());
                path.add(displayName);
                if (path.size() > ManifestEntry.MAX_DEPTH) {
                    throw new IOException("Selected directory tree is too deep");
                }
                Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, documentId);
                children.add(fromCursor(cursor, columns, documentUri, documentId, path));
            }
        } catch (SecurityException denied) {
            throw new IOException("Access to the selected directory was revoked", denied);
        } catch (IllegalArgumentException invalidProviderData) {
            throw new IOException("Document provider returned invalid metadata", invalidProviderData);
        }
        Collections.sort(children, DOCUMENT_ORDER);
        return children;
    }

    private SafDocument queryDocument(Uri uri, List<String> path) throws IOException {
        requireUriBelongsToTree(uri);
        try (Cursor cursor = resolver.query(uri, PROJECTION, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new FileNotFoundException("SAF document no longer exists");
            }
            ColumnIndexes columns = new ColumnIndexes(cursor);
            String documentId = requiredColumn(cursor, columns.documentId, "document id");
            validateDocumentId(documentId);
            if (!documentId.equals(DocumentsContract.getDocumentId(uri))) {
                throw new IOException(
                        "Document provider returned metadata for a different document");
            }
            SafDocument document = fromCursor(cursor, columns, uri, documentId, path);
            if (cursor.moveToNext()) {
                throw new IOException(
                        "Document provider returned multiple rows for one document");
            }
            return document;
        } catch (SecurityException denied) {
            throw new IOException("Access to the selected directory was revoked", denied);
        } catch (IllegalArgumentException invalidProviderData) {
            throw new IOException("Document provider returned invalid metadata", invalidProviderData);
        }
    }

    private static SafDocument fromCursor(
            Cursor cursor,
            ColumnIndexes columns,
            Uri uri,
            String documentId,
            List<String> path) throws IOException {
        String displayName = requiredColumn(cursor, columns.displayName, "display name");
        String mimeType = cursor.isNull(columns.mimeType)
                ? null : cursor.getString(columns.mimeType);
        boolean directory = Document.MIME_TYPE_DIR.equals(mimeType);
        long size = directory || cursor.isNull(columns.size) ? (directory ? 0L : -1L)
                : cursor.getLong(columns.size);
        long lastModified = cursor.isNull(columns.lastModified)
                ? 0L : Math.max(0L, cursor.getLong(columns.lastModified));
        int flags = cursor.isNull(columns.flags) ? 0 : cursor.getInt(columns.flags);
        return new SafDocument(
                uri,
                documentId,
                path,
                displayName,
                directory,
                size,
                lastModified,
                mimeType,
                flags);
    }

    private void requireBelongsToTree(SafDocument document) throws IOException {
        if (document == null) {
            throw new IOException("Document does not belong to the selected SAF tree");
        }
        requireUriBelongsToTree(document.getUri());
        try {
            if (!document.getDocumentId().equals(
                    DocumentsContract.getDocumentId(document.getUri()))) {
                throw new IOException("Document URI does not match its document id");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Document is not a valid tree document URI", invalid);
        }
    }

    private void requireUriBelongsToTree(Uri uri) throws IOException {
        if (uri == null
                || !"content".equals(uri.getScheme())
                || !authority.equals(uri.getAuthority())) {
            throw new IOException("Document URI escaped the selected SAF provider");
        }
        try {
            if (!rootDocumentId.equals(DocumentsContract.getTreeDocumentId(uri))) {
                throw new IOException("Document URI escaped the selected SAF tree");
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Document is not a valid tree document URI", invalid);
        }
    }

    private void requireDirectory(SafDocument document) throws IOException {
        requireBelongsToTree(document);
        if (!document.isDirectory()) {
            throw new IOException("SAF parent is not a directory");
        }
    }

    private static SafDocument findByExactName(
            List<SafDocument> children, String displayName) {
        for (SafDocument child : children) {
            if (displayName.equals(child.getDisplayName())) {
                return child;
            }
        }
        return null;
    }

    private static String requiredColumn(Cursor cursor, int index, String name)
            throws IOException {
        if (cursor.isNull(index)) {
            throw new IOException("Document provider omitted " + name);
        }
        String value = cursor.getString(index);
        if (value == null || value.isEmpty()) {
            throw new IOException("Document provider returned an empty " + name);
        }
        return value;
    }

    private static void validateDocumentId(String documentId) {
        if (documentId == null
                || documentId.isEmpty()
                || documentId.length() > MAX_DOCUMENT_ID_CHARS
                || documentId.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Document provider returned an invalid document id");
        }
    }

    private static CancellationToken nonNullCancellation(CancellationToken value) {
        return value == null ? CancellationToken.NONE : value;
    }

    public interface ScanObserver {
        ScanObserver NONE = new ScanObserver() {
            @Override
            public void onEntry(SafDocument entry, int discoveredEntries) {}
        };

        void onEntry(SafDocument entry, int discoveredEntries) throws IOException;
    }

    private static final Comparator<SafDocument> DOCUMENT_ORDER =
            new Comparator<SafDocument>() {
                @Override
                public int compare(SafDocument left, SafDocument right) {
                    int byName = left.getDisplayName().compareTo(right.getDisplayName());
                    return byName != 0
                            ? byName : left.getDocumentId().compareTo(right.getDocumentId());
                }
            };

    private static final class ColumnIndexes {
        final int documentId;
        final int displayName;
        final int mimeType;
        final int size;
        final int lastModified;
        final int flags;

        ColumnIndexes(Cursor cursor) {
            documentId = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID);
            displayName = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME);
            mimeType = cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE);
            size = cursor.getColumnIndexOrThrow(Document.COLUMN_SIZE);
            lastModified = cursor.getColumnIndexOrThrow(Document.COLUMN_LAST_MODIFIED);
            flags = cursor.getColumnIndexOrThrow(Document.COLUMN_FLAGS);
        }
    }

}
