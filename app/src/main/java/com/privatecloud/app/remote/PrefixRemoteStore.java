package com.privatecloud.app.remote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Confines the backup engine to an optional user-selected subdirectory of a connection root. */
final class PrefixRemoteStore implements RemoteStore {
    private final RemoteStore delegate;
    private final String prefix;

    PrefixRemoteStore(RemoteStore delegate, String prefix) {
        if (delegate == null) throw new IllegalArgumentException("delegate must not be null");
        this.delegate = delegate;
        this.prefix = RemotePaths.normalize(prefix);
        if (this.prefix.isEmpty()) throw new IllegalArgumentException("prefix must not be empty");
    }

    @Override
    public RemoteEntry stat(String remotePath) throws IOException {
        return unmap(delegate.stat(map(remotePath)));
    }

    @Override
    public boolean exists(String remotePath) throws IOException {
        return delegate.exists(map(remotePath));
    }

    @Override
    public List<RemoteEntry> list(String remoteDirectory) throws IOException {
        List<RemoteEntry> entries = delegate.list(map(remoteDirectory));
        ArrayList<RemoteEntry> result = new ArrayList<RemoteEntry>(entries.size());
        for (RemoteEntry entry : entries) result.add(unmap(entry));
        return Collections.unmodifiableList(result);
    }

    @Override
    public void download(String remotePath, OutputStream destination) throws IOException {
        delegate.download(map(remotePath), destination);
    }

    @Override
    public void upload(
            String remotePath, InputStream source, long contentLength, boolean overwrite)
            throws IOException {
        delegate.upload(map(remotePath), source, contentLength, overwrite);
    }

    @Override
    public void upload(
            String remotePath, InputStream source, long contentLength, String contentType,
            boolean overwrite) throws IOException {
        delegate.upload(map(remotePath), source, contentLength, contentType, overwrite);
    }

    @Override
    public void createDirectory(String remotePath) throws IOException {
        String safePath = RemotePaths.normalize(remotePath);
        if (safePath.isEmpty()) {
            delegate.createDirectories(prefix);
        } else {
            delegate.createDirectory(map(safePath));
        }
    }

    @Override
    public void createDirectories(String remotePath) throws IOException {
        delegate.createDirectories(map(remotePath));
    }

    @Override
    public void delete(String remotePath) throws IOException {
        String safePath = RemotePaths.normalize(remotePath);
        if (safePath.isEmpty()) throw new IOException("Refusing to delete configured remote root");
        delegate.delete(map(safePath));
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    private String map(String path) {
        String safePath = RemotePaths.normalize(path);
        return safePath.isEmpty() ? prefix : RemotePaths.join(prefix, safePath);
    }

    private RemoteEntry unmap(RemoteEntry entry) throws IOException {
        String path = entry.getPath();
        final String relative;
        if (path.equals(prefix)) {
            relative = "";
        } else if (path.startsWith(prefix + "/")) {
            relative = path.substring(prefix.length() + 1);
        } else {
            throw new IOException("Remote server returned an entry outside the configured prefix");
        }
        return new RemoteEntry(
                relative,
                RemotePaths.name(relative),
                entry.isDirectory(),
                entry.getSize(),
                entry.getLastModified(),
                entry.getEtag());
    }
}
