package com.privatecloud.app.backup;

import java.io.IOException;

/** A remote snapshot marker, manifest, object, or integrity value is malformed or inconsistent. */
public final class InvalidSnapshotException extends IOException {
    private static final long serialVersionUID = 1L;

    public InvalidSnapshotException(String message) {
        super(message);
    }

    public InvalidSnapshotException(String message, Throwable cause) {
        super(message, cause);
    }
}
