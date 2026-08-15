package com.privatecloud.app.remote;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public final class PrefixRemoteStoreTest {
    @Test
    public void mapsAllOperationsBelowConfiguredPrefix() throws Exception {
        RecordingStore delegate = new RecordingStore();
        PrefixRemoteStore store = new PrefixRemoteStore(delegate, "phones/pixel");

        store.createDirectories("");
        assertEquals("phones/pixel", delegate.lastPath);

        store.createDirectories(".private-cloud/v1");
        assertEquals("phones/pixel/.private-cloud/v1", delegate.lastPath);

        store.delete(".private-cloud/v1/old");
        assertEquals("phones/pixel/.private-cloud/v1/old", delegate.lastPath);

        store.upload(
                ".private-cloud/v1/files/photo.jpg",
                new java.io.ByteArrayInputStream(new byte[0]),
                0L,
                "image/jpeg",
                false);
        assertEquals("phones/pixel/.private-cloud/v1/files/photo.jpg", delegate.lastPath);
        assertEquals("image/jpeg", delegate.lastContentType);

        delegate.entry = new RemoteEntry(
                "phones/pixel/.private-cloud", ".private-cloud", true, 0L, 0L, null);
        RemoteEntry mapped = store.stat(".private-cloud");
        assertEquals(".private-cloud", mapped.getPath());
    }

    @Test(expected = IOException.class)
    public void rejectsDelegateEntryOutsidePrefix() throws Exception {
        RecordingStore delegate = new RecordingStore();
        delegate.entry = new RemoteEntry("other/file", "file", false, 1L, 0L, null);
        new PrefixRemoteStore(delegate, "safe/root").stat("file");
    }

    private static final class RecordingStore implements RemoteStore {
        String lastPath;
        String lastContentType;
        RemoteEntry entry;

        @Override public RemoteEntry stat(String path) throws IOException {
            lastPath = path;
            if (entry != null) return entry;
            return new RemoteEntry(path, RemotePaths.name(path), true, 0L, 0L, null);
        }
        @Override public List<RemoteEntry> list(String path) {
            lastPath = path;
            return Collections.emptyList();
        }
        @Override public void download(String path, OutputStream destination) { lastPath = path; }
        @Override public void upload(
                String path, InputStream source, long length, boolean overwrite) {
            lastPath = path;
        }
        @Override public void upload(
                String path, InputStream source, long length, String contentType,
                boolean overwrite) {
            lastPath = path;
            lastContentType = contentType;
        }
        @Override public void createDirectory(String path) { lastPath = path; }
        @Override public void createDirectories(String path) { lastPath = path; }
        @Override public void delete(String path) { lastPath = path; }
        @Override public void close() {}
    }
}
