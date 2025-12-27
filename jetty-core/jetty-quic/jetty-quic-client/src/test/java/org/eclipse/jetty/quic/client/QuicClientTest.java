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

package org.eclipse.jetty.quic.client;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.quiche.server.QuicheServerConnector;
import org.eclipse.jetty.quic.quiche.server.QuicheServerQuicConfiguration;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HostHeaderCustomizer;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class QuicClientTest
{
    private Server server;
    private QuicheServerConnector connector;

    private void start(Path workDir, Handler handler) throws Exception
    {
        server = new Server();

        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(MavenPaths.findTestResourceFile("keystore.p12").toString());
        sslContextFactory.setKeyStorePassword("storepwd");

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.addCustomizer(new SecureRequestCustomizer());
        httpConfig.addCustomizer(new HostHeaderCustomizer());

        QuicheServerQuicConfiguration serverQuicConfig = new QuicheServerQuicConfiguration(workDir);
        connector = new QuicheServerConnector(server, sslContextFactory, serverQuicConfig, new HttpConnectionFactory(httpConfig));
        server.addConnector(connector);

        server.setHandler(handler);

        server.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testConnect(@TempDir Path workDir) throws Exception
    {
        start(workDir, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                callback.succeeded();
                return true;
            }
        });

        QuicClient client = new QuicClient();
        client.start();

        Promise.Completable<Session> completable = new Promise.Completable<>();
        client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), null, completable);
        Session session = completable.get(5, TimeUnit.SECONDS);
        assertNotNull(session);
    }
}
