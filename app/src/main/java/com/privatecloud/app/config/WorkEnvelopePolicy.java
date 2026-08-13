package com.privatecloud.app.config;

import java.nio.charset.StandardCharsets;

/** WorkManager Data boundary and AAD policy for encrypted operation envelopes. */
final class WorkEnvelopePolicy {
    static final int MAX_ENVELOPE_BYTES = 6 * 1024;

    private WorkEnvelopePolicy() {}

    static String aad(String canonicalJobKey) {
        return "work-operation|schema=1|id=" + JobKey.requireCanonical(canonicalJobKey);
    }

    static void requireFits(String envelope) {
        if (envelope == null
                || envelope.getBytes(StandardCharsets.UTF_8).length > MAX_ENVELOPE_BYTES) {
            throw new IllegalArgumentException("后台任务配置过长");
        }
    }
}
