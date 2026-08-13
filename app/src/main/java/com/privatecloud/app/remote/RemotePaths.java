package com.privatecloud.app.remote;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/** Path validation and RFC 3986 encoding shared by both transports. */
final class RemotePaths {
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final int MAX_PATH_CHARS = 4096;
    private static final int MAX_SEGMENT_CHARS = 255;

    private RemotePaths() {}

    static String normalize(String input) {
        if (input == null) {
            throw new IllegalArgumentException("remotePath must not be null");
        }
        if (input.length() > MAX_PATH_CHARS + 2) {
            throw new IllegalArgumentException("remotePath is too long");
        }

        String value = Normalizer.normalize(input, Normalizer.Form.NFC);
        if (value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Backslashes are not allowed in remote paths");
        }
        if (value.startsWith("//")) {
            throw new IllegalArgumentException("Network or authority paths are not allowed");
        }
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.isEmpty()) {
            return "";
        }
        if (value.length() > MAX_PATH_CHARS) {
            throw new IllegalArgumentException("remotePath is too long");
        }

        String[] segments = value.split("/", -1);
        StringBuilder normalized = new StringBuilder(value.length());
        for (String segment : segments) {
            validateSegment(segment);
            if (normalized.length() > 0) {
                normalized.append('/');
            }
            normalized.append(segment);
        }
        return normalized.toString();
    }

    static String join(String parent, String child) {
        String safeParent = normalize(parent);
        String safeChild = normalize(child);
        if (safeParent.isEmpty()) {
            return safeChild;
        }
        if (safeChild.isEmpty()) {
            return safeParent;
        }
        return normalize(safeParent + "/" + safeChild);
    }

    static String parent(String path) {
        String safePath = normalize(path);
        int slash = safePath.lastIndexOf('/');
        return slash < 0 ? "" : safePath.substring(0, slash);
    }

    static String name(String path) {
        String safePath = normalize(path);
        int slash = safePath.lastIndexOf('/');
        return slash < 0 ? safePath : safePath.substring(slash + 1);
    }

    static String encodePath(String path) {
        String safePath = normalize(path);
        if (safePath.isEmpty()) {
            return "";
        }
        String[] segments = safePath.split("/", -1);
        StringBuilder encoded = new StringBuilder(safePath.length());
        for (String segment : segments) {
            if (encoded.length() > 0) {
                encoded.append('/');
            }
            encoded.append(encodeSegment(segment));
        }
        return encoded.toString();
    }

    static String decodeUriPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return "";
        }
        StringBuilder decoded = new StringBuilder(rawPath.length());
        int index = 0;
        while (index < rawPath.length()) {
            char current = rawPath.charAt(index);
            if (current != '%') {
                decoded.append(current);
                index++;
                continue;
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (index < rawPath.length() && rawPath.charAt(index) == '%') {
                if (index + 2 >= rawPath.length()) {
                    throw new IllegalArgumentException("Malformed percent escape in remote URI");
                }
                int high = Character.digit(rawPath.charAt(index + 1), 16);
                int low = Character.digit(rawPath.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("Malformed percent escape in remote URI");
                }
                int decodedByte = (high << 4) | low;
                if (decodedByte == '/' || decodedByte == '\\') {
                    throw new IllegalArgumentException(
                            "Percent-encoded path separators are not allowed");
                }
                bytes.write(decodedByte);
                index += 3;
            }
            try {
                CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes.toByteArray()));
                decoded.append(chars);
            } catch (CharacterCodingException malformed) {
                throw new IllegalArgumentException("Remote URI is not valid UTF-8", malformed);
            }
        }
        return decoded.toString();
    }

    private static String encodeSegment(String segment) {
        byte[] bytes = segment.getBytes(StandardCharsets.UTF_8);
        StringBuilder encoded = new StringBuilder(bytes.length);
        for (byte value : bytes) {
            int unsigned = value & 0xff;
            if ((unsigned >= 'a' && unsigned <= 'z')
                    || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9')
                    || unsigned == '-'
                    || unsigned == '.'
                    || unsigned == '_'
                    || unsigned == '~') {
                encoded.append((char) unsigned);
            } else {
                encoded.append('%');
                encoded.append(HEX[unsigned >>> 4]);
                encoded.append(HEX[unsigned & 0x0f]);
            }
        }
        return encoded.toString();
    }

    private static void validateSegment(String segment) {
        if (segment.isEmpty()) {
            throw new IllegalArgumentException("Empty path segments are not allowed");
        }
        if (segment.equals(".") || segment.equals("..")) {
            throw new IllegalArgumentException("Dot path segments are not allowed");
        }
        if (segment.length() > MAX_SEGMENT_CHARS) {
            throw new IllegalArgumentException("Remote path segment is too long");
        }
        for (int offset = 0; offset < segment.length();) {
            int codePoint = segment.codePointAt(offset);
            if (codePoint == 0
                    || codePoint == 0x7f
                    || codePoint < 0x20
                    || Character.getType(codePoint) == Character.SURROGATE) {
                throw new IllegalArgumentException("Control or invalid characters are not allowed");
            }
            offset += Character.charCount(codePoint);
        }
    }
}
