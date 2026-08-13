package com.privatecloud.app.remote;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class RemotePathsTest {
    @Test
    public void normalizesAndEncodesUnicodeSegments() {
        assertEquals("相册/2026 夏天", RemotePaths.normalize("/相册/2026 夏天/"));
        assertEquals(
                "%E7%9B%B8%E5%86%8C/2026%20%E5%A4%8F%E5%A4%A9",
                RemotePaths.encodePath("相册/2026 夏天"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTraversal() {
        RemotePaths.normalize("photos/../secret");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEncodedSeparatorAfterDecode() {
        RemotePaths.normalize(RemotePaths.decodeUriPath("safe%2Fescape"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptySegment() {
        RemotePaths.normalize("a//b");
    }
}
