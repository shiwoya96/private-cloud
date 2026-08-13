package com.privatecloud.app.remote;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import jcifs.CIFSContext;
import jcifs.CIFSException;
import jcifs.SmbConstants;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

/** SMB2/SMB3 remote store backed by {@code eu.agno3.jcifs:jcifs-ng:2.1.10}. */
public final class SmbStore implements RemoteStore {
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 15_000;
    private static final int DEFAULT_RESPONSE_TIMEOUT_MS = 60_000;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private final String baseUrl;
    private final CIFSContext baseContext;
    private final CIFSContext authenticatedContext;
    private volatile boolean closed;

    public SmbStore(String baseUrl, String domain, String username, String password)
            throws IOException {
        this(baseUrl, domain, username, password,
                DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_RESPONSE_TIMEOUT_MS);
    }

    public SmbStore(
            String baseUrl,
            String domain,
            String username,
            String password,
            int connectTimeoutMs,
            int responseTimeoutMs) throws IOException {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        validateCredential(domain, "domain", true);
        validateCredential(username, "username", false);
        validateCredential(password, "password", false);
        if (connectTimeoutMs <= 0 || responseTimeoutMs <= 0) {
            throw new IllegalArgumentException("Timeouts must be positive");
        }

        Properties properties = new Properties();
        // SMB1 is deliberately excluded; modern NAS devices negotiate SMB2/3 here.
        properties.setProperty("jcifs.smb.client.minVersion", "SMB202");
        properties.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        properties.setProperty("jcifs.smb.client.connTimeout",
                Integer.toString(connectTimeoutMs));
        properties.setProperty("jcifs.smb.client.responseTimeout",
                Integer.toString(responseTimeoutMs));
        properties.setProperty("jcifs.smb.client.soTimeout",
                Integer.toString(responseTimeoutMs));
        properties.setProperty("jcifs.smb.client.sessionTimeout",
                Integer.toString(responseTimeoutMs));
        properties.setProperty("jcifs.smb.client.strictResourceLifecycle", "true");

        final PropertyConfiguration configuration;
        try {
            configuration = new PropertyConfiguration(properties);
        } catch (CIFSException invalidConfiguration) {
            throw new IOException("Unable to configure SMB client", invalidConfiguration);
        }
        this.baseContext = new BaseContext(configuration);
        if (username.isEmpty()) {
            this.authenticatedContext = this.baseContext.withAnonymousCredentials();
        } else {
            this.authenticatedContext = this.baseContext.withCredentials(
                    new NtlmPasswordAuthenticator(
                            domain == null ? "" : domain, username, password));
        }
    }

    @Override
    public RemoteEntry stat(String remotePath) throws IOException {
        String safePath = RemotePaths.normalize(remotePath);
        boolean directoryHint = safePath.isEmpty()
                || (remotePath != null && remotePath.endsWith("/"));
        RemoteEntry entry = statIfExists(safePath, directoryHint);
        if (entry == null && !directoryHint) {
            // jCIFS requires collection URLs to end in '/'. Retry that shape only after
            // the file-shaped lookup reported absence.
            entry = statIfExists(safePath, true);
        }
        if (entry == null) {
            throw notFound(safePath);
        }
        return entry;
    }

    @Override
    public boolean exists(String remotePath) throws IOException {
        String safePath = RemotePaths.normalize(remotePath);
        boolean directoryHint = safePath.isEmpty()
                || (remotePath != null && remotePath.endsWith("/"));
        if (existsOnce(safePath, directoryHint)) {
            return true;
        }
        return !directoryHint && existsOnce(safePath, true);
    }

    @Override
    public List<RemoteEntry> list(String remoteDirectory) throws IOException {
        String safeDirectory = RemotePaths.normalize(remoteDirectory);
        List<RemoteEntry> entries = new ArrayList<>();
        try (SmbFile directory = resource(safeDirectory, true)) {
            if (!directory.exists()) {
                throw notFound(safeDirectory);
            }
            if (!directory.isDirectory()) {
                throw new IOException("Remote SMB path is not a directory: " + safeDirectory);
            }
            SmbFile[] children = directory.listFiles();
            if (children == null) {
                throw new IOException("SMB server returned no directory listing");
            }
            try {
                for (int index = 0; index < children.length; index++) {
                    SmbFile child = children[index];
                    if (child == null) {
                        continue;
                    }
                    try (SmbFile closeableChild = child) {
                        String childName = closeableChild.getName();
                        while (childName.endsWith("/")) {
                            childName = childName.substring(0, childName.length() - 1);
                        }
                        if (childName.isEmpty() || childName.indexOf('/') >= 0) {
                            throw new IOException("SMB server returned an invalid child name");
                        }
                        String childPath;
                        try {
                            childPath = RemotePaths.join(safeDirectory, childName);
                        } catch (IllegalArgumentException unsafeName) {
                            throw new IOException(
                                    "SMB server returned an unsafe child name", unsafeName);
                        }
                        boolean isDirectory = closeableChild.isDirectory();
                        entries.add(new RemoteEntry(
                                childPath,
                                childName,
                                isDirectory,
                                isDirectory ? 0L : closeableChild.length(),
                                Math.max(0L, closeableChild.lastModified()),
                                null));
                    } finally {
                        // Avoid closing a successfully processed resource again below.
                        children[index] = null;
                    }
                }
            } finally {
                closeRemaining(children);
            }
        }
        Collections.sort(entries, new Comparator<RemoteEntry>() {
            @Override
            public int compare(RemoteEntry left, RemoteEntry right) {
                return left.getName().compareTo(right.getName());
            }
        });
        return Collections.unmodifiableList(entries);
    }

    @Override
    public void download(String remotePath, OutputStream destination) throws IOException {
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        String safePath = requireFilePath(remotePath);
        try (SmbFile file = resource(safePath, false)) {
            if (!file.exists()) {
                throw notFound(safePath);
            }
            if (file.isDirectory()) {
                throw new IOException("Cannot download an SMB directory: " + safePath);
            }
            try (InputStream input = file.openInputStream()) {
                copy(input, destination);
            }
        }
    }

    @Override
    public void upload(
            String remotePath,
            InputStream source,
            long contentLength,
            boolean overwrite) throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("source must not be null");
        }
        if (contentLength < -1L) {
            throw new IllegalArgumentException("contentLength must be -1 or non-negative");
        }
        String safePath = requireFilePath(remotePath);
        try (SmbFile file = resource(safePath, false)) {
            // jcifs-ng 2.1.10 implements createNewFile() with SMB2 FILE_OPEN_IF,
            // which may open an existing file. O_EXCL maps to FILE_CREATE and makes
            // the no-overwrite decision atomically on the server.
            try (OutputStream output = overwrite
                    ? file.openOutputStream(false)
                    : file.openOutputStream(
                            false,
                            SmbConstants.O_CREAT
                                    | SmbConstants.O_EXCL
                                    | SmbConstants.O_WRONLY,
                            0,
                            SmbConstants.FILE_SHARE_READ)) {
                copy(source, output);
            }
        }
    }

    @Override
    public void createDirectory(String remotePath) throws IOException {
        String safePath = RemotePaths.normalize(remotePath);
        if (safePath.isEmpty()) {
            return;
        }
        try (SmbFile directory = resource(safePath, true)) {
            if (directory.exists()) {
                if (!directory.isDirectory()) {
                    throw new IOException("Remote SMB path is not a directory: " + safePath);
                }
                return;
            }
            directory.mkdir();
        }
    }

    @Override
    public void createDirectories(String remotePath) throws IOException {
        String safePath = RemotePaths.normalize(remotePath);
        if (safePath.isEmpty()) {
            return;
        }
        try (SmbFile directory = resource(safePath, true)) {
            if (directory.exists()) {
                if (!directory.isDirectory()) {
                    throw new IOException("Remote SMB path is not a directory: " + safePath);
                }
                return;
            }
            directory.mkdirs();
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            baseContext.close();
        } catch (CIFSException closeError) {
            throw new IOException("Unable to close SMB client", closeError);
        }
    }

    private SmbFile resource(String safePath, boolean directory) throws IOException {
        requireOpen();
        StringBuilder url = new StringBuilder(baseUrl);
        url.append(RemotePaths.encodePath(safePath));
        if (directory && url.charAt(url.length() - 1) != '/') {
            url.append('/');
        }
        return new SmbFile(url.toString(), authenticatedContext);
    }

    private RemoteEntry statIfExists(String safePath, boolean directoryHint) throws IOException {
        try (SmbFile file = resource(safePath, directoryHint)) {
            if (!file.exists()) {
                return null;
            }
            boolean directory = file.isDirectory();
            return new RemoteEntry(
                    safePath,
                    RemotePaths.name(safePath),
                    directory,
                    directory ? 0L : file.length(),
                    Math.max(0L, file.lastModified()),
                    null);
        }
    }

    private boolean existsOnce(String safePath, boolean directoryHint) throws IOException {
        try (SmbFile file = resource(safePath, directoryHint)) {
            return file.exists();
        }
    }

    private void requireOpen() throws IOException {
        if (closed) {
            throw new IOException("SMB store is closed");
        }
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null) {
            throw new IllegalArgumentException("baseUrl must not be null");
        }
        final URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException malformed) {
            throw new IllegalArgumentException("Invalid SMB base URL", malformed);
        }
        if (uri.getScheme() == null || !uri.getScheme().equalsIgnoreCase("smb")) {
            throw new IllegalArgumentException("SMB base URL must use the smb scheme");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new IllegalArgumentException("SMB base URL must include a valid host");
        }
        if (uri.getRawUserInfo() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "Credentials, query strings, and fragments are not allowed in the SMB URL");
        }
        if (uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("SMB base URL contains an invalid port");
        }

        final String basePath;
        try {
            basePath = RemotePaths.normalize(RemotePaths.decodeUriPath(uri.getRawPath()));
        } catch (IllegalArgumentException unsafe) {
            throw new IllegalArgumentException("Invalid SMB share/base path", unsafe);
        }
        if (basePath.isEmpty()) {
            throw new IllegalArgumentException("SMB base URL must include a share name");
        }

        String host = uri.getHost().toLowerCase(Locale.US);
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        StringBuilder normalized = new StringBuilder("smb://").append(host);
        if (uri.getPort() >= 0) {
            normalized.append(':').append(uri.getPort());
        }
        normalized.append('/').append(RemotePaths.encodePath(basePath)).append('/');
        return normalized.toString();
    }

    private static void validateCredential(String value, String field, boolean nullable) {
        if (value == null) {
            if (nullable) {
                return;
            }
            throw new IllegalArgumentException(field + " must not be null");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == 0 || character == '\r' || character == '\n') {
                throw new IllegalArgumentException(field + " contains an invalid character");
            }
        }
    }

    private static String requireFilePath(String path) {
        String safePath = RemotePaths.normalize(path);
        if (safePath.isEmpty()) {
            throw new IllegalArgumentException("A file path must not be empty");
        }
        return safePath;
    }

    private static FileNotFoundException notFound(String safePath) {
        return new FileNotFoundException("Remote path does not exist: " + safePath);
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }

    private static void closeRemaining(SmbFile[] children) {
        for (SmbFile child : children) {
            if (child == null) {
                continue;
            }
            try {
                child.close();
            } catch (RuntimeException ignored) {
                // Preserve the operation failure that caused this cleanup path.
            }
        }
    }
}
