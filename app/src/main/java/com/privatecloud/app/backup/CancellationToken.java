package com.privatecloud.app.backup;

import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe cooperative cancellation checked during traversal and every streamed chunk. */
public interface CancellationToken {
    CancellationToken NONE = new CancellationToken() {
        @Override
        public boolean isCancellationRequested() {
            return false;
        }
    };

    boolean isCancellationRequested();

    default void throwIfCancellationRequested() throws OperationCancelledException {
        if (isCancellationRequested() || Thread.currentThread().isInterrupted()) {
            throw new OperationCancelledException();
        }
    }

    /** Mutable token owned by a controller. Workers receive it as a CancellationToken. */
    final class Source implements CancellationToken {
        private final AtomicBoolean cancelled = new AtomicBoolean();

        public void cancel() {
            cancelled.set(true);
        }

        @Override
        public boolean isCancellationRequested() {
            return cancelled.get();
        }
    }
}
