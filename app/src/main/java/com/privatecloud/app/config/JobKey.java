package com.privatecloud.app.config;

import java.util.Locale;
import java.util.UUID;

/** Canonicalizes the WorkRequest UUID used to bind an encrypted operation envelope. */
final class JobKey {
    private JobKey() {}

    static String requireCanonical(String value) {
        if (value == null) throw new IllegalArgumentException("Missing background job key");
        final UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Invalid background job key", invalid);
        }
        String normalized = parsed.toString();
        if (!normalized.equals(value.toLowerCase(Locale.US)) || value.length() != 36) {
            throw new IllegalArgumentException("Background job key is not canonical");
        }
        return normalized;
    }
}
