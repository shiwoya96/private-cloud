package com.privatecloud.app.remote;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/** WebDAV store implemented with OkHttp 5.x public APIs. */
public final class WebDavStore implements RemoteStore {
    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 15_000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 60_000;
    private static final int COPY_BUFFER_BYTES = 32 * 1024;
    private static final int MAX_XML_BYTES = 8 * 1024 * 1024;
    private static final int MAX_ERROR_BYTES = 1024;
    private static final int HTTP_MOVED_PERMANENTLY = 301;
    private static final int HTTP_FOUND = 302;
    private static final int HTTP_SEE_OTHER = 303;
    private static final int HTTP_TEMPORARY_REDIRECT = 307;
    private static final int HTTP_PERMANENT_REDIRECT = 308;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_METHOD_NOT_ALLOWED = 405;
    private static final int HTTP_NOT_IMPLEMENTED = 501;
    private static final MediaType XML_MEDIA_TYPE =
            MediaType.get("application/xml; charset=utf-8");
    private static final MediaType OCTET_STREAM_MEDIA_TYPE =
            MediaType.get("application/octet-stream");
    private static final byte[] PROPFIND_BODY = (
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                    + "<d:propfind xmlns:d=\"DAV:\"><d:prop>"
                    + "<d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/>"
                    + "</d:prop></d:propfind>")
            .getBytes(StandardCharsets.UTF_8);
    private static final RequestBody PROPFIND_REQUEST_BODY =
            new ByteArrayRequestBody(PROPFIND_BODY, XML_MEDIA_TYPE);

    private final URI baseUri;
    private final String authorization;
    private final OkHttpClient client;
    private volatile boolean closed;

    public WebDavStore(String baseUrl, String username, String password) {
        this(URI.create(baseUrl), username, password,
                DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS);
    }

    public WebDavStore(
            URI baseUri,
            String username,
            String password,
            int connectTimeoutMs,
            int readTimeoutMs) {
        this.baseUri = normalizeBaseUri(baseUri);
        validateCredential(username, "username", true);
        validateCredential(password, "password", false);
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException("Timeouts must be positive");
        }
        // Do not send an empty Basic credential to servers configured for anonymous access.
        // When either field is present, keep the explicit credential (including an empty
        // username, which some application-password deployments intentionally use).
        this.authorization = username.isEmpty() && password.isEmpty()
                ? null : Credentials.basic(username, password, StandardCharsets.UTF_8);
        this.client = new OkHttpClient.Builder()
                .connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(readTimeoutMs, TimeUnit.MILLISECONDS)
                // Credentials are attached per request. Never allow them to follow a redirect.
                .followRedirects(false)
                .followSslRedirects(false)
                .build();
    }

    /** Performs WebDAV PROPFIND Depth: 0, falling back to HEAD when unsupported. */
    @Override
    public RemoteEntry stat(String remotePath) throws IOException {
        String safePath = RemotePaths.normalize(remotePath);
        boolean directoryHint = safePath.isEmpty()
                || (remotePath != null && remotePath.endsWith("/"));
        try {
            return statWithPropfind(safePath, directoryHint);
        } catch (RemoteStoreException error) {
            if (error.getStatusCode() == HTTP_METHOD_NOT_ALLOWED
                    || error.getStatusCode() == HTTP_NOT_IMPLEMENTED) {
                return head(remotePath);
            }
            throw error;
        }
    }

    /** Performs an HTTP HEAD without following redirects. */
    public RemoteEntry head(String remotePath) throws IOException {
        String safePath = RemotePaths.normalize(remotePath);
        boolean directoryHint = safePath.isEmpty()
                || (remotePath != null && remotePath.endsWith("/"));
        try {
            return headOnce(safePath, directoryHint);
        } catch (FileNotFoundException notFound) {
            if (!directoryHint && !safePath.isEmpty()) {
                return headOnce(safePath, true);
            }
            throw notFound;
        } catch (RemoteStoreException error) {
            if (isRedirect(error.getStatusCode()) && !directoryHint && !safePath.isEmpty()) {
                return headOnce(safePath, true);
            }
            throw error;
        }
    }

    @Override
    public boolean exists(String remotePath) throws IOException {
        String safePath = RemotePaths.normalize(remotePath);
        try {
            head(remotePath);
            return true;
        } catch (FileNotFoundException notFound) {
            return false;
        } catch (RemoteStoreException error) {
            if (error.getStatusCode() == HTTP_METHOD_NOT_ALLOWED
                    || error.getStatusCode() == HTTP_NOT_IMPLEMENTED) {
                try {
                    statWithPropfind(safePath, remotePath != null && remotePath.endsWith("/"));
                    return true;
                } catch (FileNotFoundException notFound) {
                    return false;
                }
            }
            throw error;
        }
    }

    @Override
    public List<RemoteEntry> list(String remoteDirectory) throws IOException {
        String safeDirectory = RemotePaths.normalize(remoteDirectory);
        List<RemoteEntry> response = propfind(safeDirectory, 1);
        List<RemoteEntry> children = new ArrayList<>();
        for (RemoteEntry entry : response) {
            if (!entry.getPath().equals(safeDirectory)
                    && RemotePaths.parent(entry.getPath()).equals(safeDirectory)) {
                children.add(entry);
            }
        }
        Collections.sort(children, byName());
        return Collections.unmodifiableList(children);
    }

    /** Performs a GET and copies the response to a caller-owned stream. */
    public void get(String remotePath, OutputStream destination) throws IOException {
        if (destination == null) {
            throw new IllegalArgumentException("destination must not be null");
        }
        String safePath = requireFilePath(remotePath);
        Request request = requestBuilder(safePath, false)
                .get()
                .build();
        try (Response response = client.newCall(request).execute()) {
            requireSuccess(response, "GET", safePath);
            ResponseBody body = requireBody(response, "GET", safePath);
            try (InputStream input = body.byteStream()) {
                copy(input, destination);
            }
        }
    }

    @Override
    public void download(String remotePath, OutputStream destination) throws IOException {
        get(remotePath, destination);
    }

    /** Performs a PUT. A non-negative length enables fixed-length streaming. */
    public void put(
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
        Request.Builder builder = requestBuilder(safePath, false);
        if (!overwrite) {
            builder.header("If-None-Match", "*");
        }
        Request request = builder
                .method("PUT", new StreamRequestBody(source, contentLength))
                .build();
        try (Response response = client.newCall(request).execute()) {
            requireSuccess(response, "PUT", safePath);
        }
    }

    @Override
    public void upload(
            String remotePath,
            InputStream source,
            long contentLength,
            boolean overwrite) throws IOException {
        put(remotePath, source, contentLength, overwrite);
    }

    /** Performs MKCOL for one collection. HTTP 405 is accepted only when it already exists. */
    public void mkcol(String remotePath) throws IOException {
        String safePath = RemotePaths.normalize(remotePath);
        if (safePath.isEmpty()) {
            return;
        }
        Request request = requestBuilder(safePath, true)
                .method("MKCOL", null)
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (response.code() == HTTP_METHOD_NOT_ALLOWED) {
                try {
                    RemoteEntry existing = propfindStat(safePath, true);
                    if (existing.isDirectory()) {
                        return;
                    }
                } catch (FileNotFoundException ignored) {
                    // Preserve the original MKCOL failure below.
                }
            }
            requireSuccess(response, "MKCOL", safePath);
        }
    }

    @Override
    public void createDirectory(String remotePath) throws IOException {
        mkcol(remotePath);
    }

    @Override
    public void createDirectories(String remotePath) throws IOException {
        String safePath = RemotePaths.normalize(remotePath);
        if (safePath.isEmpty()) {
            return;
        }
        String current = "";
        for (String segment : safePath.split("/")) {
            current = RemotePaths.join(current, segment);
            mkcol(current);
        }
    }

    /** Performs PROPFIND with Depth 0 or 1 and parses a DAV multistatus response. */
    public List<RemoteEntry> propfind(String remotePath, int depth) throws IOException {
        if (depth != 0 && depth != 1) {
            throw new IllegalArgumentException("Only WebDAV Depth 0 and 1 are allowed");
        }
        String safePath = RemotePaths.normalize(remotePath);
        boolean directoryHint = depth == 1 || safePath.isEmpty()
                || (remotePath != null && remotePath.endsWith("/"));
        Request request = requestBuilder(safePath, directoryHint)
                .header("Depth", Integer.toString(depth))
                .method("PROPFIND", PROPFIND_REQUEST_BODY)
                .build();
        try (Response response = client.newCall(request).execute()) {
            requireSuccess(response, "PROPFIND", safePath);
            ResponseBody body = requireBody(response, "PROPFIND", safePath);
            byte[] xml = readLimited(body.byteStream(), MAX_XML_BYTES);
            return parseMultistatus(xml);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        client.dispatcher().cancelAll();
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }

    private Request.Builder requestBuilder(String safePath, boolean directory) throws IOException {
        requireOpen();
        Request.Builder builder = new Request.Builder()
                .url(resolve(safePath, directory).toASCIIString())
                .header("Accept", "*/*")
                .header("User-Agent", "PrivateCloud/1.0");
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        return builder;
    }

    private RemoteEntry headOnce(String safePath, boolean directory) throws IOException {
        Request request = requestBuilder(safePath, directory)
                .method("HEAD", null)
                .build();
        try (Response response = client.newCall(request).execute()) {
            requireSuccess(response, "HEAD", safePath);
            String contentType = response.header("Content-Type");
            boolean isDirectory = directory || (contentType != null
                    && contentType.toLowerCase(Locale.US).contains("directory"));
            return new RemoteEntry(
                    safePath,
                    RemotePaths.name(safePath),
                    isDirectory,
                    parseNonNegativeLong(response.header("Content-Length"), -1L),
                    parseHttpDate(response.header("Last-Modified")),
                    response.header("ETag"));
        }
    }

    private RemoteEntry statWithPropfind(String safePath, boolean directoryHint)
            throws IOException {
        try {
            return propfindStat(safePath, directoryHint);
        } catch (FileNotFoundException notFound) {
            if (!directoryHint && !safePath.isEmpty()) {
                return propfindStat(safePath, true);
            }
            throw notFound;
        } catch (RemoteStoreException error) {
            if (isRedirect(error.getStatusCode()) && !directoryHint && !safePath.isEmpty()) {
                return propfindStat(safePath, true);
            }
            throw error;
        }
    }

    private RemoteEntry propfindStat(String safePath, boolean directory) throws IOException {
        String requestPath = directory && !safePath.isEmpty() ? safePath + "/" : safePath;
        return findStatEntry(safePath, propfind(requestPath, 0));
    }

    private static ResponseBody requireBody(Response response, String method, String safePath)
            throws IOException {
        ResponseBody body = response.body();
        if (body == null) {
            throw new IOException("WebDAV " + method + " returned no body for '" + safePath + "'");
        }
        return body;
    }

    private URI resolve(String safePath, boolean directory) {
        StringBuilder value = new StringBuilder(baseUri.toASCIIString());
        value.append(RemotePaths.encodePath(safePath));
        if (directory && value.charAt(value.length() - 1) != '/') {
            value.append('/');
        }
        return URI.create(value.toString());
    }

    private List<RemoteEntry> parseMultistatus(byte[] xml) throws IOException {
        if (xml.length == 0) {
            throw new IOException("WebDAV returned an empty PROPFIND response");
        }
        final String xmlText = decodeStrictUtf8(xml);
        String lowerXml = xmlText.toLowerCase(Locale.US);
        if (lowerXml.contains("<!doctype") || lowerXml.contains("<!entity")) {
            throw new IOException("DTD and entity declarations are not allowed in WebDAV XML");
        }

        final Document document;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            trySetFeature(factory,
                    "http://apache.org/xml/features/disallow-doctype-decl", true);
            trySetFeature(factory,
                    "http://xml.org/sax/features/external-general-entities", false);
            trySetFeature(factory,
                    "http://xml.org/sax/features/external-parameter-entities", false);
            trySetFeature(factory,
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) ->
                    new InputSource(new StringReader("")));
            document = builder.parse(new InputSource(new StringReader(xmlText)));
        } catch (ParserConfigurationException | SAXException parseError) {
            throw new IOException("Invalid WebDAV PROPFIND XML", parseError);
        }

        List<RemoteEntry> entries = new ArrayList<>();
        NodeList responses = descendants(document.getDocumentElement(), "response");
        for (int index = 0; index < responses.getLength(); index++) {
            Node node = responses.item(index);
            if (!(node instanceof Element)) {
                continue;
            }
            Element response = (Element) node;
            String responseStatus = directChildText(response, "status");
            if (responseStatus != null && !isSuccessfulDavStatus(responseStatus)) {
                continue;
            }
            String href = directChildText(response, "href");
            if (href == null || href.isEmpty()) {
                continue;
            }
            String path = relativePathFromHref(href);
            if (path == null) {
                continue;
            }

            List<Element> properties = successfulProperties(response);
            if (properties.isEmpty()) {
                continue;
            }
            boolean directory = false;
            String contentLength = null;
            String lastModified = null;
            String etag = null;
            for (Element property : properties) {
                directory |= descendants(property, "collection").getLength() > 0;
                if (contentLength == null) {
                    contentLength = firstText(property, "getcontentlength");
                }
                if (lastModified == null) {
                    lastModified = firstText(property, "getlastmodified");
                }
                if (etag == null) {
                    etag = firstText(property, "getetag");
                }
            }
            long size = directory ? 0L
                    : parseNonNegativeLong(contentLength, -1L);
            long modified = parseHttpDate(lastModified);
            entries.add(new RemoteEntry(
                    path,
                    RemotePaths.name(path),
                    directory,
                    size,
                    modified,
                    etag));
        }
        Collections.sort(entries, new Comparator<RemoteEntry>() {
            @Override
            public int compare(RemoteEntry left, RemoteEntry right) {
                return left.getPath().compareTo(right.getPath());
            }
        });
        return Collections.unmodifiableList(entries);
    }

    private List<Element> successfulProperties(Element response) {
        List<Element> result = new ArrayList<>();
        NodeList children = response.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (!(node instanceof Element) || !hasLocalName(node, "propstat")) {
                continue;
            }
            Element propstat = (Element) node;
            if (isSuccessfulDavStatus(directChildText(propstat, "status"))) {
                Element property = directChildElement(propstat, "prop");
                if (property != null) {
                    result.add(property);
                }
            }
        }
        if (result.isEmpty()) {
            Element directProperty = directChildElement(response, "prop");
            if (directProperty != null) {
                result.add(directProperty);
            }
        }
        return result;
    }

    private String relativePathFromHref(String href) throws IOException {
        final URI resolved;
        try {
            resolved = baseUri.resolve(new URI(href.trim())).normalize();
        } catch (URISyntaxException | IllegalArgumentException malformed) {
            throw new IOException("WebDAV returned an invalid href", malformed);
        }
        if (!sameOrigin(baseUri, resolved)) {
            return null;
        }

        final String decodedPath;
        try {
            decodedPath = RemotePaths.decodeUriPath(resolved.getRawPath());
        } catch (IllegalArgumentException malformed) {
            throw new IOException("WebDAV href contains an invalid encoded path", malformed);
        }
        String basePath = baseUri.getPath();
        String baseWithoutSlash = basePath.length() > 1
                ? basePath.substring(0, basePath.length() - 1) : basePath;
        if (decodedPath.equals(baseWithoutSlash)) {
            return "";
        }
        if (!decodedPath.startsWith(basePath)) {
            return null;
        }
        try {
            return RemotePaths.normalize(decodedPath.substring(basePath.length()));
        } catch (IllegalArgumentException unsafe) {
            throw new IOException("WebDAV href escaped the configured remote root", unsafe);
        }
    }

    private static URI normalizeBaseUri(URI input) {
        if (input == null) {
            throw new IllegalArgumentException("baseUri must not be null");
        }
        String scheme = input.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("WebDAV base URL must use HTTP or HTTPS");
        }
        if (input.getHost() == null || input.getHost().isEmpty()) {
            throw new IllegalArgumentException("WebDAV base URL must include a valid host");
        }
        if (input.getRawUserInfo() != null
                || input.getRawQuery() != null
                || input.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "Credentials, query strings, and fragments are not allowed in the base URL");
        }
        if (input.getPort() == 0 || input.getPort() > 65535) {
            throw new IllegalArgumentException("WebDAV base URL contains an invalid port");
        }

        final String safeBasePath;
        try {
            safeBasePath = RemotePaths.normalize(
                    RemotePaths.decodeUriPath(input.getRawPath()));
        } catch (IllegalArgumentException unsafe) {
            throw new IllegalArgumentException("Invalid WebDAV base path", unsafe);
        }
        // The component constructor performs the percent-encoding. Passing an already
        // encoded path here would turn '%' into "%25" and change the configured root.
        String path = safeBasePath.isEmpty() ? "/" : "/" + safeBasePath + "/";
        try {
            return new URI(
                    scheme.toLowerCase(Locale.US),
                    null,
                    input.getHost(),
                    input.getPort(),
                    path,
                    null,
                    null).normalize();
        } catch (URISyntaxException impossible) {
            throw new IllegalArgumentException("Invalid WebDAV base URL", impossible);
        }
    }

    private static boolean sameOrigin(URI left, URI right) {
        return right.getScheme() != null
                && right.getHost() != null
                && left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private static RemoteEntry findStatEntry(
            String safePath, List<RemoteEntry> entries) throws FileNotFoundException {
        for (RemoteEntry entry : entries) {
            if (entry.getPath().equals(safePath)) {
                return entry;
            }
        }
        throw notFound(safePath);
    }

    private static boolean isRedirect(int status) {
        return status == HTTP_MOVED_PERMANENTLY
                || status == HTTP_FOUND
                || status == HTTP_SEE_OTHER
                || status == HTTP_TEMPORARY_REDIRECT
                || status == HTTP_PERMANENT_REDIRECT;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static void validateCredential(String value, String field, boolean username) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        if (username && value.indexOf(':') >= 0) {
            throw new IllegalArgumentException("Basic Auth username must not contain ':'");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\r' || character == '\n' || character == 0) {
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

    private void requireOpen() throws IOException {
        if (closed) {
            throw new IOException("WebDAV store is closed");
        }
    }

    private static void requireSuccess(Response response, String method, String safePath)
            throws IOException {
        int status = response.code();
        if (status >= 200 && status < 300) {
            return;
        }
        if (status == HTTP_NOT_FOUND) {
            throw notFound(safePath);
        }
        String detail = readErrorDetail(response);
        String message = "WebDAV " + method + " failed for '" + safePath
                + "' with HTTP " + status;
        if (!detail.isEmpty()) {
            message += ": " + detail;
        }
        throw new RemoteStoreException("webdav", status, message);
    }

    private static FileNotFoundException notFound(String safePath) {
        return new FileNotFoundException("Remote path does not exist: " + safePath);
    }

    private static String readErrorDetail(Response response) {
        ResponseBody body = response.body();
        if (body == null) {
            return "";
        }
        try (InputStream input = body.byteStream()) {
            byte[] bytes = readLimited(input, MAX_ERROR_BYTES);
            String value = new String(bytes, StandardCharsets.UTF_8)
                    .replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            return value.length() > 240 ? value.substring(0, 240) : value;
        } catch (IOException ignored) {
            return "";
        }
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 16 * 1024));
        byte[] buffer = new byte[8 * 1024];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) {
                throw new IOException("Remote response exceeded " + limit + " bytes");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String decodeStrictUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("WebDAV PROPFIND XML must be valid UTF-8", malformed);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
    }

    private static long parseNonNegativeLong(String value, long fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed >= 0L ? parsed : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long parseHttpDate(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0L;
        }
        SimpleDateFormat format = new SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
        format.setLenient(false);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        try {
            return format.parse(value.trim()).getTime();
        } catch (ParseException ignored) {
            return 0L;
        }
    }

    private static boolean isSuccessfulDavStatus(String value) {
        if (value == null) {
            return false;
        }
        String[] parts = value.trim().split("\\s+");
        if (parts.length < 2) {
            return false;
        }
        try {
            int status = Integer.parseInt(parts[1]);
            return status >= 200 && status < 300;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static NodeList descendants(Element parent, String localName) {
        NodeList namespaced = parent.getElementsByTagNameNS("*", localName);
        return namespaced.getLength() > 0
                ? namespaced : parent.getElementsByTagName(localName);
    }

    private static Comparator<RemoteEntry> byName() {
        return new Comparator<RemoteEntry>() {
            @Override
            public int compare(RemoteEntry left, RemoteEntry right) {
                return left.getName().compareTo(right.getName());
            }
        };
    }

    private static String firstText(Element parent, String localName) {
        NodeList nodes = descendants(parent, localName);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return value == null ? null : value.trim();
    }

    private static String directChildText(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (hasLocalName(child, localName)) {
                String value = child.getTextContent();
                return value == null ? null : value.trim();
            }
        }
        return null;
    }

    private static Element directChildElement(Element parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node child = children.item(index);
            if (child instanceof Element && hasLocalName(child, localName)) {
                return (Element) child;
            }
        }
        return null;
    }

    private static boolean hasLocalName(Node node, String localName) {
        String actualLocalName = node.getLocalName();
        return localName.equals(actualLocalName)
                || (actualLocalName == null && localName.equals(node.getNodeName()));
    }

    private static void trySetFeature(
            DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (ParserConfigurationException ignored) {
            // The input is independently screened for DTD/entity declarations above.
        }
    }

    private static final class ByteArrayRequestBody extends RequestBody {
        private final byte[] content;
        private final MediaType contentType;

        ByteArrayRequestBody(byte[] content, MediaType contentType) {
            this.content = content;
            this.contentType = contentType;
        }

        @Override
        public MediaType contentType() {
            return contentType;
        }

        @Override
        public long contentLength() {
            return content.length;
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            sink.write(content);
        }
    }

    private static final class StreamRequestBody extends RequestBody {
        private final InputStream source;
        private final long length;

        StreamRequestBody(InputStream source, long length) {
            this.source = source;
            this.length = length;
        }

        @Override
        public MediaType contentType() {
            return OCTET_STREAM_MEDIA_TYPE;
        }

        @Override
        public long contentLength() {
            return length;
        }

        @Override
        public boolean isOneShot() {
            return true;
        }

        @Override
        public void writeTo(BufferedSink sink) throws IOException {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            int count;
            while ((count = source.read(buffer)) != -1) {
                sink.write(buffer, 0, count);
            }
        }
    }
}
