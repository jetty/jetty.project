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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.server.RawHTTP3ServerConnectionFactory;
import org.eclipse.jetty.quic.server.ServerQuicConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HTTP3ClientServerTest
{
    @Test
    public void testGETThenResponseWithoutContent() throws Exception
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        sslContextFactory.setKeyStorePassword("storepwd");

        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        Server server = new Server(serverThreads);

        CountDownLatch serverLatch = new CountDownLatch(1);
        ServerQuicConnector connector = new ServerQuicConnector(server, sslContextFactory, new RawHTTP3ServerConnectionFactory(new Session.Server.Listener()
        {
            @Override
            public Stream.Listener onHeaders(Stream stream, HeadersFrame frame)
            {
                serverLatch.countDown();
                return null;
            }
        }));
        server.addConnector(connector);
        server.start();

        HTTP3Client client = new HTTP3Client();
        client.start();

        Session.Client session = client.connect(new InetSocketAddress("localhost", connector.getLocalPort()), new Session.Client.Listener() {})
            .get(555, TimeUnit.SECONDS);

        HttpURI uri = HttpURI.from("https://localhost:" + connector.getLocalPort());
        MetaData.Request metaData = new MetaData.Request(HttpMethod.GET.asString(), uri, HttpVersion.HTTP_3, HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData);
        Stream stream = session.newStream(frame, new Stream.Listener() {})
            .get(555, TimeUnit.SECONDS);
        assertNotNull(stream);

        assertTrue(serverLatch.await(555, TimeUnit.SECONDS));
    }
}
