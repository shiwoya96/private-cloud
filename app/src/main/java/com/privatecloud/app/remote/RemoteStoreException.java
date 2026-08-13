package com.privatecloud.app.remote;

import java.io.IOException;

/** An error returned by a remote protocol endpoint. */
public final class RemoteStoreException extends IOException {
    private static final long serialVersionUID = 1L;

    private final String protocol;
    private final int statusCode;

    public RemoteStoreException(String protocol, int statusCode, String message) {
        super(message);
        this.protocol = protocol;
        this.statusCode = statusCode;
    }

    public RemoteStoreException(String protocol, int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.protocol = protocol;
        this.statusCode = statusCode;
    }

    public String getProtocol() {
        return protocol;
    }

    /** HTTP status for WebDAV, or -1 when the protocol has no comparable status code. */
    public int getStatusCode() {
        return statusCode;
    }
}
