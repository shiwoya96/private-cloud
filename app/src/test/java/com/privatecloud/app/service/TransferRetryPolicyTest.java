package com.privatecloud.app.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.privatecloud.app.backup.OperationCancelledException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import org.junit.Test;

public final class TransferRetryPolicyTest {
    @Test public void retriesOnlyDocumentedTransientStatuses() {
        assertTrue(TransferRetryPolicy.isTransientStatus(408));
        assertTrue(TransferRetryPolicy.isTransientStatus(429));
        assertTrue(TransferRetryPolicy.isTransientStatus(503));
        assertFalse(TransferRetryPolicy.isTransientStatus(401));
        assertFalse(TransferRetryPolicy.isTransientStatus(507));
    }

    @Test public void retriesNestedSocketFailureButNeverCancellation() {
        assertTrue(TransferRetryPolicy.isTransientFailure(
                new IOException("wrapped", new SocketTimeoutException("timeout"))));
        assertFalse(TransferRetryPolicy.isTransientFailure(new IOException("local file")));
        assertFalse(TransferRetryPolicy.isTransientFailure(new OperationCancelledException()));
    }
}
