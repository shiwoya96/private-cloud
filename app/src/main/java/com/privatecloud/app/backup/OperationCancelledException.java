package com.privatecloud.app.backup;

import java.io.InterruptedIOException;

/** Signals cooperative cancellation without classifying the operation as a retryable I/O error. */
public final class OperationCancelledException extends InterruptedIOException {
    private static final long serialVersionUID = 1L;

    public OperationCancelledException() {
        super("Operation was cancelled");
    }
}
