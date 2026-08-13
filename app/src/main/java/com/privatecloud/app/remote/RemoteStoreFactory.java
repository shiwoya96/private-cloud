package com.privatecloud.app.remote;

import com.privatecloud.app.config.ConnectionSettings;

import java.io.IOException;

/** Opens the protocol adapter selected in persisted settings. */
public final class RemoteStoreFactory {
    private RemoteStoreFactory() {}

    public static RemoteStore open(ConnectionSettings settings) throws IOException {
        RemoteStore store;
        if (settings.getProtocol() == ConnectionSettings.Protocol.SMB) {
            store = new SmbStore(
                    settings.smbBaseUrl(),
                    settings.getSmbDomain(),
                    settings.getUsername(),
                    settings.getPassword());
        } else {
            store = new WebDavStore(
                    settings.getWebDavUrl(),
                    settings.getUsername(),
                    settings.getPassword());
        }
        return settings.getRemotePath().isEmpty()
                ? store : new PrefixRemoteStore(store, settings.getRemotePath());
    }
}
