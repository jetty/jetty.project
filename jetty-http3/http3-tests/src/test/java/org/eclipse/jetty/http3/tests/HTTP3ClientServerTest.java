//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.tests;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.server.RawHTTP3ServerConnectionFactory;
import org.eclipse.jetty.quic.server.ServerQuicConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HTTP3ClientServerTest
{
    private Server server;
    private ServerQuicConnector connector;
    private HTTP3Client client;

    private void startServer(Session.Server.Listener listener) throws Exception
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        sslContextFactory.setKeyStorePassword("storepwd");
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        connector = new ServerQuicConnector(server, sslContextFactory, new RawHTTP3ServerConnectionFactory(listener));
        server.addConnector(connector);
        server.start();
    }

    private void startClient() throws Exception
    {
        client = new HTTP3Client();
        client.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(client);
        LifeCycle.stop(server);
    }

    @Test
    public void testConnectTriggersSettingsFrame() throws Exception
    {
        CountDownLatch serverPrefaceLatch = new CountDownLatch(1);
        CountDownLatch serverSettingsLatch = new CountDownLatch(1);
        startServer(new Session.Server.Listener()
        {
            @Override
            public Map<Long, Long> onPreface(Session session)
            {
                serverPrefaceLatch.countDown();
                return Map.of();
            }

            @Override
            public void onSettings(Session session, SettingsFrame frame)
            {
                serverSettingsLatch.countDown();
            }
        });
        startClient();

        CountDownLatch clientPrefaceLatch = new CountDownLatch(1);
        CountDownLatch clientSettingsLatch = new CountDownLatch(1);
        Session.Client session = client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Client.Listener()
            {
                @Override
                public Map<Long, Long> onPreface(Session session)
                {
                    clientPrefaceLatch.countDown();
                    return Map.of();
                }

                @Override
                public void onSettings(Session session, SettingsFrame frame)
                {
                    clientSettingsLatch.countDown();
                }
            })
            .get(5, TimeUnit.SECONDS);
        assertNotNull(session);
    }

    @Test
    public void testGETThenResponseWithoutContent() throws Exception
    {
        CountDownLatch serverRequestLatch = new CountDownLatch(1);
        startServer(new Session.Server.Listener()
        {
            @Override
            public Stream.Listener onRequest(Stream stream, HeadersFrame frame)
            {
                serverRequestLatch.countDown();
                // Send the response.
                stream.respond(new HeadersFrame(new MetaData.Response(HttpVersion.HTTP_3, HttpStatus.OK_200, HttpFields.EMPTY)));
                // Not interested in request data.
                return null;
            }
        });
        startClient();

        Session.Client session = client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Client.Listener() {})
            .get(5, TimeUnit.SECONDS);

        CountDownLatch clientResponseLatch = new CountDownLatch(1);
        HttpURI uri = HttpURI.from("https://localhost:" + connector.getLocalPort() + "/");
        MetaData.Request metaData = new MetaData.Request(HttpMethod.GET.asString(), uri, HttpVersion.HTTP_3, HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData);
        Stream stream = session.newRequest(frame, new Stream.Listener()
            {
                @Override
                public void onResponse(Stream stream, HeadersFrame frame)
                {
                    clientResponseLatch.countDown();
                }
            })
            .get(555, TimeUnit.SECONDS);
        assertNotNull(stream);

        assertTrue(serverRequestLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientResponseLatch.await(5, TimeUnit.SECONDS));
    }
}
