package com.privatecloud.app.service;

import com.privatecloud.app.backup.OperationCancelledException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** Conservative retry classification shared by manual and periodic transfers. */
public final class TransferRetryPolicy {
    private TransferRetryPolicy() {}

    public static boolean isTransientStatus(int statusCode) {
        return statusCode == 408 || statusCode == 429
                || statusCode == 500 || statusCode == 502
                || statusCode == 503 || statusCode == 504;
    }

    public static boolean isTransientFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof OperationCancelledException) return false;
            if (current instanceof SocketTimeoutException
                    || current instanceof ConnectException
                    || current instanceof NoRouteToHostException
                    || current instanceof UnknownHostException
                    || current instanceof SocketException) {
                return true;
            }
        }
        return false;
    }
}
