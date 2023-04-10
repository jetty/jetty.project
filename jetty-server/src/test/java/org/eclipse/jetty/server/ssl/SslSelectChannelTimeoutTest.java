//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.ssl;

import java.io.FileInputStream;
import java.io.InputStream;
import java.net.Socket;
import java.security.KeyStore;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.eclipse.jetty.server.ConnectorTimeoutTest;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.tests.test.resources.TestKeyStoreFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.BeforeEach;

public class SslSelectChannelTimeoutTest extends ConnectorTimeoutTest
{
    static SSLContext __sslContext;

    @Override
    protected Socket newSocket(String host, int port) throws Exception
    {
        return __sslContext.getSocketFactory().createSocket(host, port);
    }

    @BeforeEach
    public void init() throws Exception
    {
        SslContextFactory.Server serverSslContextFactory = new SslContextFactory.Server();
        serverSslContextFactory.setKeyStore(TestKeyStoreFactory.getServerKeyStore());
        serverSslContextFactory.setKeyStorePassword(TestKeyStoreFactory.KEY_STORE_PASSWORD);
        serverSslContextFactory.setTrustStore(TestKeyStoreFactory.getTrustStore());
        serverSslContextFactory.setTrustStorePassword(TestKeyStoreFactory.KEY_STORE_PASSWORD);
        ServerConnector connector = new ServerConnector(_server, 1, 1, serverSslContextFactory);
        connector.setIdleTimeout(MAX_IDLE_TIME); //250 msec max idle
        startServer(connector);

        SslContextFactory.Client clientSslContextFactory = new SslContextFactory.Client();
        clientSslContextFactory.setKeyStore(TestKeyStoreFactory.getClientKeyStore());
        clientSslContextFactory.setKeyStorePassword(TestKeyStoreFactory.KEY_STORE_PASSWORD);
        clientSslContextFactory.setTrustStore(TestKeyStoreFactory.getTrustStore());
        clientSslContextFactory.setTrustStorePassword(TestKeyStoreFactory.KEY_STORE_PASSWORD);
        clientSslContextFactory.start();

        __sslContext = clientSslContextFactory.getSslContext();
        clientSslContextFactory.stop();
    }
}
