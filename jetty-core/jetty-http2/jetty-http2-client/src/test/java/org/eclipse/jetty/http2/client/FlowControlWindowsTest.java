//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.client;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ISession;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.server.RawHTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FlowControlWindowsTest
{
    private Server server;
    private ServerConnector connector;
    private HTTP2Client client;
    private int serverSessionRecvWindow = 3 * 1024 * 1024;
    private int serverStreamRecvWindow = 2 * 1024 * 1024;
    private int clientSessionRecvWindow = 5 * 1024 * 1024;
    private int clientStreamRecvWindow = 4 * 1024 * 1024;

    private void start(ServerSessionListener listener) throws Exception
    {
        RawHTTP2ServerConnectionFactory connectionFactory = new RawHTTP2ServerConnectionFactory(new HttpConfiguration(), listener);
        connectionFactory.setInitialSessionRecvWindow(serverSessionRecvWindow);
        connectionFactory.setInitialStreamRecvWindow(serverStreamRecvWindow);
        QueuedThreadPool serverExecutor = new QueuedThreadPool();
        serverExecutor.setName("server");
        server = new Server(serverExecutor);
        connector = new ServerConnector(server, 1, 1, connectionFactory);
        server.addConnector(connector);
        server.start();

        client = new HTTP2Client();
        QueuedThreadPool clientExecutor = new QueuedThreadPool();
        clientExecutor.setName("client");
        client.setExecutor(clientExecutor);
        client.setInitialSessionRecvWindow(clientSessionRecvWindow);
        client.setInitialStreamRecvWindow(clientStreamRecvWindow);
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (client != null)
            client.stop();
        if (server != null)
            server.stop();
    }

    protected ISession newClient(Session.Listener listener) throws Exception
    {
        String host = "localhost";
        int port = connector.getLocalPort();
        InetSocketAddress address = new InetSocketAddress(host, port);
        FuturePromise<Session> promise = new FuturePromise<>();
        client.connect(address, listener, promise);
        return (ISession)promise.get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testClientFlowControlWindows() throws Exception
    {
        start(new ServerSessionListener.Adapter());

        ISession clientSession = newClient(new Session.Listener.Adapter());
        // Wait while client and server exchange SETTINGS and WINDOW_UPDATE frames.
        Thread.sleep(1000);

        int sessionSendWindow = clientSession.updateSendWindow(0);
        assertEquals(serverSessionRecvWindow, sessionSendWindow);
        int sessionRecvWindow = clientSession.updateRecvWindow(0);
        assertEquals(clientSessionRecvWindow, sessionRecvWindow);

        HostPortHttpField hostPort = new HostPortHttpField("localhost:" + connector.getLocalPort());
        MetaData.Request request = new MetaData.Request(HttpMethod.GET.asString(), HttpScheme.HTTP.asString(), hostPort, "/", HttpVersion.HTTP_2, HttpFields.EMPTY, -1);
        HeadersFrame frame = new HeadersFrame(request, null, true);
        FuturePromise<Stream> promise = new FuturePromise<>();
        clientSession.newStream(frame, promise, new Stream.Listener.Adapter());
        IStream clientStream = (IStream)promise.get(5, TimeUnit.SECONDS);

        int streamSendWindow = clientStream.updateSendWindow(0);
        assertEquals(serverStreamRecvWindow, streamSendWindow);
        int streamRecvWindow = clientStream.updateRecvWindow(0);
        assertEquals(clientStreamRecvWindow, streamRecvWindow);
    }

    @Test
    public void testServerFlowControlWindows() throws Exception
    {
        AtomicReference<ISession> sessionRef = new AtomicReference<>();
        CountDownLatch sessionLatch = new CountDownLatch(1);
        AtomicReference<IStream> streamRef = new AtomicReference<>();
        CountDownLatch streamLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public void onAccept(Session session)
            {
                sessionRef.set((ISession)session);
                sessionLatch.countDown();
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                streamRef.set((IStream)stream);
                streamLatch.countDown();
                return null;
            }
        });

        ISession clientSession = newClient(new Session.Listener.Adapter());

        assertTrue(sessionLatch.await(5, TimeUnit.SECONDS));
        ISession serverSession = sessionRef.get();
        // Wait while client and server exchange SETTINGS and WINDOW_UPDATE frames.
        Thread.sleep(1000);

        int sessionSendWindow = serverSession.updateSendWindow(0);
        assertEquals(clientSessionRecvWindow, sessionSendWindow);
        int sessionRecvWindow = serverSession.updateRecvWindow(0);
        assertEquals(serverSessionRecvWindow, sessionRecvWindow);

        HostPortHttpField hostPort = new HostPortHttpField("localhost:" + connector.getLocalPort());
        MetaData.Request request = new MetaData.Request(HttpMethod.GET.asString(), HttpScheme.HTTP.asString(), hostPort, "/", HttpVersion.HTTP_2, HttpFields.EMPTY, -1);
        HeadersFrame frame = new HeadersFrame(request, null, true);
        clientSession.newStream(frame, new Promise.Adapter<>(), new Stream.Listener.Adapter());

        assertTrue(streamLatch.await(5, TimeUnit.SECONDS));
        IStream serverStream = streamRef.get();

        int streamSendWindow = serverStream.updateSendWindow(0);
        assertEquals(clientStreamRecvWindow, streamSendWindow);
        int streamRecvWindow = serverStream.updateRecvWindow(0);
        assertEquals(serverStreamRecvWindow, streamRecvWindow);
    }
}
