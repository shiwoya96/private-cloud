package com.privatecloud.app.backup;

import static org.junit.Assert.assertEquals;

import com.privatecloud.app.model.ManifestEntry;
import com.privatecloud.app.model.SnapshotInfo;
import com.privatecloud.app.model.SnapshotManifest;
import com.privatecloud.app.remote.RemoteEntry;
import com.privatecloud.app.remote.RemoteStore;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class SnapshotRepositoryTest {
    private static final String PLAN = "12345678-abcd";
    private static final String SNAPSHOT =
            "1770000000000-12345678-1234-1234-1234-123456789abc";

    @Test
    public void onlyCompleteValidatedSnapshotIsVisible() throws Exception {
        FakeRemoteStore remote = committedStore();

        List<SnapshotInfo> snapshots = new SnapshotRepository(remote).listCommitted(PLAN);

        assertEquals(1, snapshots.size());
        assertEquals(SNAPSHOT, snapshots.get(0).getSnapshotId());
    }

    @Test
    public void missingCompleteMarkerLeavesSnapshotInvisible() throws Exception {
        FakeRemoteStore remote = committedStore();
        remote.files.remove(SnapshotLayout.completePath(PLAN, SNAPSHOT));

        assertEquals(0, new SnapshotRepository(remote).listCommitted(PLAN).size());
    }

    @Test(expected = InvalidSnapshotException.class)
    public void tamperedManifestIsRejected() throws Exception {
        FakeRemoteStore remote = committedStore();
        String path = SnapshotLayout.manifestPath(PLAN, SNAPSHOT);
        byte[] bytes = remote.files.get(path).clone();
        bytes[bytes.length - 2] ^= 1;
        remote.files.put(path, bytes);

        new SnapshotRepository(remote).loadManifest(PLAN, SNAPSHOT);
    }

    private static FakeRemoteStore committedStore() throws Exception {
        FakeRemoteStore remote = new FakeRemoteStore();
        remote.createDirectories(SnapshotLayout.objectsRoot(PLAN, SNAPSHOT));
        ManifestEntry file = ManifestEntry.file(
                Collections.singletonList("hello.txt"),
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                5L,
                0L,
                "text/plain",
                SnapshotFormat.sha256("hello".getBytes("UTF-8")));
        SnapshotManifest manifest = new SnapshotManifest(
                PLAN, SNAPSHOT, 1770000000000L, "source", Collections.singletonList(file));
        byte[] manifestBytes = manifest.toJsonBytes();
        SnapshotInfo info = new SnapshotInfo(
                PLAN,
                SNAPSHOT,
                manifest.getCreatedAt(),
                manifest.getSourceName(),
                manifest.getFileCount(),
                manifest.getDirectoryCount(),
                manifest.getTotalBytes(),
                manifestBytes.length,
                SnapshotFormat.sha256(manifestBytes));
        remote.files.put(SnapshotLayout.manifestPath(PLAN, SNAPSHOT), manifestBytes);
        remote.files.put(SnapshotLayout.completePath(PLAN, SNAPSHOT),
                SnapshotFormat.encodeComplete(info));
        return remote;
    }

    private static final class FakeRemoteStore implements RemoteStore {
        final Map<String, byte[]> files = new HashMap<String, byte[]>();
        final List<String> directories = new ArrayList<String>();

        @Override public RemoteEntry stat(String path) throws IOException {
            if (files.containsKey(path)) {
                return new RemoteEntry(path, name(path), false, files.get(path).length, 0L, null);
            }
            if (directories.contains(path)) {
                return new RemoteEntry(path, name(path), true, 0L, 0L, null);
            }
            throw new FileNotFoundException(path);
        }

        @Override public List<RemoteEntry> list(String directory) {
            ArrayList<RemoteEntry> result = new ArrayList<RemoteEntry>();
            String prefix = directory.isEmpty() ? "" : directory + "/";
            for (String child : directories) {
                if (child.startsWith(prefix)
                        && child.indexOf('/', prefix.length()) < 0
                        && !child.equals(directory)) {
                    result.add(new RemoteEntry(child, name(child), true, 0L, 0L, null));
                }
            }
            return result;
        }

        @Override public void download(String path, OutputStream destination) throws IOException {
            byte[] value = files.get(path);
            if (value == null) throw new FileNotFoundException(path);
            destination.write(value);
        }

        @Override public void upload(
                String path, InputStream source, long length, boolean overwrite) throws IOException {
            if (!overwrite && files.containsKey(path)) throw new IOException("exists");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[128];
            int read;
            while ((read = source.read(buffer)) != -1) output.write(buffer, 0, read);
            files.put(path, output.toByteArray());
        }

        @Override public void createDirectory(String path) {
            if (!directories.contains(path)) directories.add(path);
        }

        @Override public void createDirectories(String path) {
            String current = "";
            for (String segment : path.split("/")) {
                current = current.isEmpty() ? segment : current + "/" + segment;
                createDirectory(current);
            }
        }

        @Override public void close() {}

        private static String name(String path) {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? path : path.substring(slash + 1);
        }
    }
}
