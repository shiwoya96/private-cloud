package com.privatecloud.app.config;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Immutable public and secret fields required to open one remote connection. */
public final class ConnectionSettings {
    public enum Protocol { WEBDAV, SMB }

    private final Protocol protocol;
    private final String webDavUrl;
    private final String smbHost;
    private final int smbPort;
    private final String smbShare;
    private final String smbDomain;
    private final String username;
    private final String password;
    private final String remotePath;
    private final String planId;

    public ConnectionSettings(
            Protocol protocol,
            String webDavUrl,
            String smbHost,
            int smbPort,
            String smbShare,
            String smbDomain,
            String username,
            String password,
            String remotePath,
            String planId) {
        if (protocol == null) {
            throw new IllegalArgumentException("protocol must not be null");
        }
        if (smbPort < 1 || smbPort > 65535) {
            throw new IllegalArgumentException("SMB port must be in 1..65535");
        }
        this.protocol = protocol;
        this.webDavUrl = safe(webDavUrl);
        this.smbHost = safe(smbHost);
        this.smbPort = smbPort;
        this.smbShare = safe(smbShare);
        this.smbDomain = safe(smbDomain);
        this.username = safe(username);
        this.password = safe(password);
        this.remotePath = normalizeRelativePath(remotePath);
        this.planId = requireId(planId);
        if (protocol == Protocol.SMB) {
            validateSmbHost(this.smbHost);
            validateSmbShare(this.smbShare);
        }
    }

    public Protocol getProtocol() { return protocol; }
    public String getWebDavUrl() { return webDavUrl; }
    public String getSmbHost() { return smbHost; }
    public int getSmbPort() { return smbPort; }
    public String getSmbShare() { return smbShare; }
    public String getSmbDomain() { return smbDomain; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getRemotePath() { return remotePath; }
    public String getPlanId() { return planId; }

    public String smbBaseUrl() {
        String host = smbHost;
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        StringBuilder value = new StringBuilder("smb://").append(host);
        if (smbPort != 445) {
            value.append(':').append(smbPort);
        }
        value.append('/').append(encodeSegment(smbShare));
        value.append('/');
        return value.toString();
    }

    private static String normalizeRelativePath(String value) {
        String input = safe(value).trim();
        if (input.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Backslashes are not allowed in remote paths");
        }
        while (input.startsWith("/")) input = input.substring(1);
        while (input.endsWith("/")) input = input.substring(0, input.length() - 1);
        if (input.isEmpty()) return "";
        String[] parts = input.split("/", -1);
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)
                    || part.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("Unsafe remote path");
            }
            if (result.length() > 0) result.append('/');
            result.append(part);
        }
        return result.toString();
    }

    private static void validateSmbHost(String host) {
        if (host.isEmpty()) throw new IllegalArgumentException("SMB host is required");
        for (int index = 0; index < host.length(); index++) {
            char value = host.charAt(index);
            if (value <= 0x20 || value == '/' || value == '\\' || value == '@'
                    || value == '?' || value == '#') {
                throw new IllegalArgumentException("Invalid SMB host");
            }
        }
    }

    private static void validateSmbShare(String share) {
        if (share.isEmpty() || ".".equals(share) || "..".equals(share)
                || share.indexOf('/') >= 0 || share.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid SMB share");
        }
    }

    private static String encodeSegment(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        final char[] hex = "0123456789ABCDEF".toCharArray();
        for (byte item : bytes) {
            int unsigned = item & 0xff;
            if ((unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || unsigned == '-' || unsigned == '.' || unsigned == '_'
                    || unsigned == '~') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%').append(hex[unsigned >>> 4]).append(hex[unsigned & 0x0f]);
            }
        }
        return encoded.toString();
    }

    private static String requireId(String value) {
        String id = safe(value).toLowerCase(Locale.US);
        if (!id.matches("[a-z0-9-]{8,64}")) {
            throw new IllegalArgumentException("Invalid plan id");
        }
        return id;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
