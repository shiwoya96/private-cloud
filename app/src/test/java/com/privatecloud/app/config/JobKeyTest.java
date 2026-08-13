package com.privatecloud.app.config;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class JobKeyTest {
    @Test
    public void canonicalizesUuidCaseWithoutChangingNamespace() {
        assertEquals(
                "01234567-89ab-cdef-0123-456789abcdef",
                JobKey.requireCanonical("01234567-89AB-CDEF-0123-456789ABCDEF"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonCanonicalUuidAcceptedByUuidParser() {
        JobKey.requireCanonical("1-1-1-1-1");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonUuidBindingInput() {
        JobKey.requireCanonical("../../active_connection_password");
    }
}
