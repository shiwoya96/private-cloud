package com.privatecloud.app.config;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class ConnectionSettingsTest {
    @Test
    public void smbBaseUrlIncludesPortAndShareWhilePathRemainsAConfinedPrefix() {
        ConnectionSettings settings = new ConnectionSettings(
                ConnectionSettings.Protocol.SMB,
                "",
                "nas.lan",
                1445,
                "backup",
                "WORKGROUP",
                "alice",
                "secret",
                "/phones/pixel/",
                "12345678-abcd");

        assertEquals("smb://nas.lan:1445/backup/", settings.smbBaseUrl());
    }

    @Test
    public void smbIpv6HostIsBracketed() {
        ConnectionSettings settings = new ConnectionSettings(
                ConnectionSettings.Protocol.SMB,
                "",
                "fd00::10",
                445,
                "backup",
                "",
                "",
                "",
                "",
                "12345678-abcd");

        assertEquals("smb://[fd00::10]/backup/", settings.smbBaseUrl());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsParentTraversal() {
        new ConnectionSettings(
                ConnectionSettings.Protocol.WEBDAV,
                "https://example.test/dav/",
                "",
                445,
                "",
                "",
                "",
                "",
                "safe/../escape",
                "12345678-abcd");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidPort() {
        new ConnectionSettings(
                ConnectionSettings.Protocol.SMB,
                "",
                "nas",
                70000,
                "share",
                "",
                "",
                "",
                "",
                "12345678-abcd");
    }
}
