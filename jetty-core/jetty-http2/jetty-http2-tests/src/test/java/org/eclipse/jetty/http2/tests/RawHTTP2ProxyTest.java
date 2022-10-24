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

package org.eclipse.jetty.http2.tests;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.server.RawHTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RawHTTP2ProxyTest
{
    private static final Logger LOGGER = LoggerFactory.getLogger(RawHTTP2ProxyTest.class);

    private final List<Server> servers = new ArrayList<>();
    private final List<HTTP2Client> clients = new ArrayList<>();

    private Server startServer(String name, ServerSessionListener listener) throws Exception
    {
        QueuedThreadPool serverExecutor = new QueuedThreadPool();
        serverExecutor.setName(name);
        Server server = new Server(serverExecutor);
        RawHTTP2ServerConnectionFactory connectionFactory = new RawHTTP2ServerConnectionFactory(new HttpConfiguration(), listener);
        ServerConnector connector = new ServerConnector(server, 1, 1, connectionFactory);
        server.addConnector(connector);
        server.setAttribute("connector", connector);
        servers.add(server);
        server.start();
        return server;
    }

    private HTTP2Client startClient(String name) throws Exception
    {
        HTTP2Client client = new HTTP2Client();
        QueuedThreadPool clientExecutor = new QueuedThreadPool();
        clientExecutor.setName(name);
        client.setExecutor(clientExecutor);
        clients.add(client);
        client.start();
        return client;
    }

    @AfterEach
    public void dispose() throws Exception
    {
        for (int i = clients.size() - 1; i >= 0; i--)
        {
            HTTP2Client client = clients.get(i);
            client.stop();
        }
        for (int i = servers.size() - 1; i >= 0; i--)
        {
            Server server = servers.get(i);
            server.stop();
        }
    }

    @Test
    public void testRawHTTP2Proxy() throws Exception
    {
        byte[] data1 = new byte[1024];
        new Random().nextBytes(data1);
        ByteBuffer buffer1 = ByteBuffer.wrap(data1);
        Server server1 = startServer("server1", new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                if (LOGGER.isDebugEnabled())
                    LOGGER.debug("SERVER1 received {}", frame);
                return new Stream.Listener()
                {
                    @Override
                    public void onHeaders(Stream stream, HeadersFrame frame)
                    {
                        if (LOGGER.isDebugEnabled())
                            LOGGER.debug("SERVER1 received {}", frame);
                        if (frame.isEndStream())
                        {
                            MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                            HeadersFrame reply = new HeadersFrame(stream.getId(), response, null, false);
                            if (LOGGER.isDebugEnabled())
                                LOGGER.debug("SERVER1 sending {}", reply);
                            stream.headers(reply, new Callback()
                            {
                                @Override
                                public void succeeded()
                                {
                                    DataFrame data = new DataFrame(stream.getId(), buffer1.slice(), true);
                                    if (LOGGER.isDebugEnabled())
                                        LOGGER.debug("SERVER1 sending {}", data);
                                    stream.data(data, NOOP);
                                }
                            });
                        }
                    }
                };
            }
        });
        ServerConnector connector1 = (ServerConnector)server1.getAttribute("connector");
        Server server2 = startServer("server2", new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                if (LOGGER.isDebugEnabled())
                    LOGGER.debug("SERVER2 received {}", frame);
                stream.demand();
                return new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        Stream.Data data = stream.readData();
                        if (LOGGER.isDebugEnabled())
                            LOGGER.debug("SERVER2 received {}", data);
                        data.release();
                        MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
                        HeadersFrame reply = new HeadersFrame(stream.getId(), response, null, false);
                        if (LOGGER.isDebugEnabled())
                            LOGGER.debug("SERVER2 sending {}", reply);
                        stream.headers(reply)
                            .thenCompose(s ->
                            {
                                DataFrame dataFrame = new DataFrame(s.getId(), buffer1.slice(), false);
                                if (LOGGER.isDebugEnabled())
                                    LOGGER.debug("SERVER2 sending {}", dataFrame);
                                return s.data(dataFrame);
                            }).thenAccept(s ->
                            {
                                MetaData trailer = new MetaData(HttpVersion.HTTP_2, HttpFields.EMPTY);
                                HeadersFrame end = new HeadersFrame(s.getId(), trailer, null, true);
                                if (LOGGER.isDebugEnabled())
                                    LOGGER.debug("SERVER2 sending {}", end);
                                s.headers(end);
                            });
                    }
                };
            }
        });
        ServerConnector connector2 = (ServerConnector)server2.getAttribute("connector");
        HTTP2Client proxyClient = startClient("proxyClient");
        Server proxyServer = startServer("proxyServer", new ClientToProxySessionListener(proxyClient));
        ServerConnector proxyConnector = (ServerConnector)proxyServer.getAttribute("connector");
        InetSocketAddress proxyAddress = new InetSocketAddress("localhost", proxyConnector.getLocalPort());
        HTTP2Client client = startClient("client");

        FuturePromise<Session> clientPromise = new FuturePromise<>();
        client.connect(proxyAddress, new Session.Listener() {}, clientPromise);
        Session clientSession = clientPromise.get(5, TimeUnit.SECONDS);

        // Send a request with trailers for server1.
        HttpFields.Mutable fields1 = HttpFields.build();
        fields1.put("X-Target", String.valueOf(connector1.getLocalPort()));
        MetaData.Request request1 = new MetaData.Request("GET", HttpURI.from("http://localhost/server1"), HttpVersion.HTTP_2, fields1);
        FuturePromise<Stream> streamPromise1 = new FuturePromise<>();
        CountDownLatch latch1 = new CountDownLatch(1);
        clientSession.newStream(new HeadersFrame(request1, null, false), streamPromise1, new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (LOGGER.isDebugEnabled())
                    LOGGER.debug("CLIENT received {}", frame);
                stream.demand();
            }

            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                DataFrame frame = data.frame();
                if (LOGGER.isDebugEnabled())
                    LOGGER.debug("CLIENT received {}", frame);
                assertEquals(buffer1.slice(), frame.getData());
                data.release();
                latch1.countDown();
                if (!data.frame().isEndStream())
                    stream.demand();
            }
        });
        Stream stream1 = streamPromise1.get(5, TimeUnit.SECONDS);
        stream1.headers(new HeadersFrame(stream1.getId(), new MetaData(HttpVersion.HTTP_2, HttpFields.EMPTY), null, true), Callback.NOOP);

        // Send a request for server2.
        HttpFields.Mutable fields2 = HttpFields.build();
        fields2.put("X-Target", String.valueOf(connector2.getLocalPort()));
        MetaData.Request request2 = new MetaData.Request("GET", HttpURI.from("http://localhost/server1"), HttpVersion.HTTP_2, fields2);
        FuturePromise<Stream> streamPromise2 = new FuturePromise<>();
        CountDownLatch latch2 = new CountDownLatch(1);
        clientSession.newStream(new HeadersFrame(request2, null, false), streamPromise2, new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (LOGGER.isDebugEnabled())
                    LOGGER.debug("CLIENT received {}", frame);
                if (frame.isEndStream())
                    latch2.countDown();
                else
                    stream.demand();
            }

            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                if (LOGGER.isDebugEnabled())
                    LOGGER.debug("CLIENT received {}", data.frame());
                data.release();
                if (!data.frame().isEndStream())
                    stream.demand();
            }
        });
        Stream stream2 = streamPromise2.get(5, TimeUnit.SECONDS);
        stream2.data(new DataFrame(stream2.getId(), buffer1.slice(), true), Callback.NOOP);

        assertTrue(latch1.await(5, TimeUnit.SECONDS));
        assertTrue(latch2.await(5, TimeUnit.SECONDS));
    }

    private static class ClientToProxySessionListener implements ServerSessionListener
    {
        private final Map<Integer, ClientToProxyToServer> forwarders = new ConcurrentHashMap<>();
        private final HTTP2Client client;

        private ClientToProxySessionListener(HTTP2Client client)
        {
            this.client = client;
        }

        @Override
        public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
        {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Received {} for {} on {}: {}", frame, stream, stream.getSession(), frame.getMetaData());
            // Forward to the right server.
            MetaData metaData = frame.getMetaData();
            HttpFields fields = metaData.getFields();
            int port = Integer.parseInt(fields.get("X-Target"));
            ClientToProxyToServer clientToProxyToServer = forwarders.computeIfAbsent(port, p -> new ClientToProxyToServer("localhost", p, client));
            clientToProxyToServer.offer(stream, frame, Callback.NOOP);
            stream.demand();
            return clientToProxyToServer;
        }

        @Override
        public void onClose(Session session, GoAwayFrame frame, Callback callback)
        {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Received {} on {}", frame, session);
            // TODO
            callback.succeeded();
        }

        @Override
        public boolean onIdleTimeout(Session session)
        {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Idle timeout on {}", session);
            // TODO
            return true;
        }

        @Override
        public void onFailure(Session session, Throwable failure, Callback callback)
        {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Failure on " + session, failure);
            // TODO
            callback.succeeded();
        }
    }

    private static class ClientToProxyToServer extends IteratingCallback implements Stream.Listener
    {
        private final AutoLock lock = new AutoLock();
        private final Map<Stream, Deque<FrameInfo>> frames = new HashMap<>();
        private final Map<Stream, Stream> streams = new HashMap<>();
        private final ServerToProxyToClient serverToProxyToClient = new ServerToProxyToClient();
        private final String host;
        private final int port;
        private final HTTP2Client client;
        private Session proxyToServerSession;
        private FrameInfo frameInfo;
        private Stream clientToProxyStream;

        private ClientToProxyToServer(String host, int port, HTTP2Client client)
        {
            this.host = host;
            this.port = port;
            this.client = client;
        }

        private void offer(Stream stream, Frame frame, Callback callback)
        {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("CPS queueing {} for {} on {}", frame, stream, stream.getSession());
            boolean connected;
            try (AutoLock ignored = lock.lock())
            {
                Deque<FrameInfo> deque = frames.computeIfAbsent(stream, s -> new ArrayDeque<>());
                deque.offer(new FrameInfo(frame, callback));
                connected = proxyToServerSession != null;
            }
            if (connected)
                iterate();
            else
                connect();
        }

        private void connect()
        {
            InetSocketAddress address = new InetSocketAddress(host, port);
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("CPS connecting to {}", address);
            client.connect(address, new ServerToProxySessionListener(), new Promise<>()
            {
                @Override
                public void succeeded(Session result)
                {
                    if (LOGGER.isDebugEnabled())
                        LOGGER.debug("CPS connected to {} with {}", address, result);
                    try (AutoLock ignored = lock.lock())
                    {
                        proxyToServerSession = result;
                    }
                    iterate();
                }

                @Override
                public void failed(Throwable x)
                {
                    if (LOGGER.isDebugEnabled())
                        LOGGER.debug("CPS connect failed to {}", address);
                    // TODO: drain the queue and fail the streams.
                }
            });
        }

        @Override
        protected Action process() throws Throwable
        {
            Stream proxyToServerStream = null;
            Session proxyToServerSession = null;
            try (AutoLock ignored = lock.lock())
            {
                for (Map.Entry<Stream, Deque<FrameInfo>> entry : frames.entrySet())
                {
                    frameInfo = entry.getValue().poll();
                    if (frameInfo != null)
                    {
                        clientToProxyStream = entry.getKey();
                        proxyToServerStream = streams.get(clientToProxyStream);
                        proxyToServerSession = this.proxyToServerSession;
                        break;
                    }
                }
            }

            if (LOGGER.isDebugEnabled())
                LOGGER.debug("CPS processing {} for {} to {}", frameInfo, clientToProxyStream, proxyToServerStream);

            if (frameInfo == null)
                return Action.IDLE;

            if (proxyToServerStream == null)
            {
                HeadersFrame clientToProxyFrame = (HeadersFrame)frameInfo.frame;
                HeadersFrame proxyToServerFrame = new HeadersFrame(clientToProxyFrame.getMetaData(), clientToProxyFrame.getPriority(), clientToProxyFrame.isEndStream());
                proxyToServerSession.newStream(proxyToServerFrame, new Promise<>()
                {
                    @Override
                    public void succeeded(Stream result)
                    {
                        try (AutoLock ignored = lock.lock())
                        {
                            if (LOGGER.isDebugEnabled())
                                LOGGER.debug("CPS created {}", result);
                            streams.put(clientToProxyStream, result);
                        }
                        serverToProxyToClient.link(result, clientToProxyStream);
                        ClientToProxyToServer.this.succeeded();
                    }

                    @Override
                    public void failed(Throwable failure)
                    {
                        if (LOGGER.isDebugEnabled())
                            LOGGER.debug("CPS create failed", failure);
                        // TODO: cannot open stream to server.
                        ClientToProxyToServer.this.failed(failure);
                    }
                }, serverToProxyToClient);
                return Action.SCHEDULED;
            }
            else
            {
                if (LOGGER.isDebugEnabled())
                    LOGGER.debug("CPS forwarding {} from {} to {}", frameInfo, clientToProxyStream, proxyToServerStream);
                return switch (frameInfo.frame.getType())
                {
                    case HEADERS ->
                    {
                        HeadersFrame clientToProxyFrame = (HeadersFrame)frameInfo.frame;
                        HeadersFrame proxyToServerFrame = new HeadersFrame(proxyToServerStream.getId(), clientToProxyFrame.getMetaData(), clientToProxyFrame.getPriority(), clientToProxyFrame.isEndStream());
                        proxyToServerStream.headers(proxyToServerFrame, this);
                        yield Action.SCHEDULED;
                    }
                    case DATA ->
                    {
                        DataFrame clientToProxyFrame = (DataFrame)frameInfo.frame;
                        DataFrame proxyToServerFrame = new DataFrame(proxyToServerStream.getId(), clientToProxyFrame.getData(), clientToProxyFrame.isEndStream());
                        proxyToServerStream.data(proxyToServerFrame, this);
                        yield Action.SCHEDULED;
                    }
                    default -> throw new IllegalStateException();
                };
            }
        }

        @Override
        public void succeeded()
        {
            frameInfo.callback.succeeded();
            super.succeeded();
        }

        @Override
        public void failed(Throwable failure)
        {
            frameInfo.callback.failed(failure);
            super.failed(failure);
        }

        @Override
        public void onHeaders(Stream stream, HeadersFrame frame)
        {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("CPS received {} on {}", frame, stream);
            offer(stream, frame, NOOP);
            stream.demand();
        }

        @Override
        public Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
        {
            // Clients don't push.
            return null;
        }

        @Override
        public void onDataAvailable(Stream stream)
        {
            Stream.Data data = stream.readData();
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("CPS read {} on {}", data, stream);
            offer(stream, data.frame(), Callback.from(data::release));
            if (!data.frame().isEndStream())
                stream.demand();
        }

        @Override
        public void onReset(Stream stream, ResetFrame frame, Callback callback)
        {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("CPS received {} on {}", frame, stream);
            // TODO: drain the queue for that stream, and notify server.
            callback.succeeded();
        }

        @Override
        public void onIdleTimeout(Stream stream, Throwable x, Promise<Boolean> promise)
        {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("CPS idle timeout for {}", stream);
            // TODO: drain the queue for that stream, reset stream, and notify server.
            promise.succeeded(true);
        }
    }

    private static class ServerToProxySessionListener implements Session.Listener
    {
        @Override
        public void onClose(Session session, GoAwayFrame frame, Callback callback)
        {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Received {} on {}", frame, session);
            // TODO
            callback.succeeded();
        }

        @Override
        public boolean onIdleTimeout(Session session)
        {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Idle timeout on {}", session);
            // TODO
            return true;
        }

        @Override
        public void onFailure(Session session, Throwable failure, Callback callback)
        {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Failure on " + session, failure);
            // TODO
            callback.succeeded();
        }
    }

    private static class ServerToProxyToClient extends IteratingCallback implements Stream.Listener
    {
        private final AutoLock lock = new AutoLock();
        private final Map<Stream, Deque<FrameInfo>> frames = new HashMap<>();
        private final Map<Stream, Stream> streams = new HashMap<>();
        private FrameInfo frameInfo;
        private Stream serverToProxyStream;

        @Override
        protected Action process() throws Throwable
        {
            Stream proxyToClientStream = null;
            try (AutoLock ignored = lock.lock())
            {
                for (Map.Entry<Stream, Deque<FrameInfo>> entry : frames.entrySet())
                {
                    frameInfo = entry.getValue().poll();
                    if (frameInfo != null)
                    {
                        serverToProxyStream = entry.getKey();
                        proxyToClientStream = streams.get(serverToProxyStream);
                        break;
                    }
                }
            }

            if (LOGGER.isDebugEnabled())
                LOGGER.debug("SPC processing {} for {} to {}", frameInfo, serverToProxyStream, proxyToClientStream);

            // It may happen that we received a frame from the server,
            // but the proxyToClientStream is not linked yet.
            if (proxyToClientStream == null)
                return Action.IDLE;

            if (LOGGER.isDebugEnabled())
                LOGGER.debug("SPC forwarding {} for {} to {}", frameInfo, serverToProxyStream, proxyToClientStream);

            return switch (frameInfo.frame.getType())
            {
                case HEADERS ->
                {
                    HeadersFrame serverToProxyFrame = (HeadersFrame)frameInfo.frame;
                    HeadersFrame proxyToClientFrame = new HeadersFrame(proxyToClientStream.getId(), serverToProxyFrame.getMetaData(), serverToProxyFrame.getPriority(), serverToProxyFrame.isEndStream());
                    proxyToClientStream.headers(proxyToClientFrame, this);
                    yield Action.SCHEDULED;
                }
                case DATA ->
                {
                    DataFrame clientToProxyFrame = (DataFrame)frameInfo.frame;
                    DataFrame proxyToServerFrame = new DataFrame(serverToProxyStream.getId(), clientToProxyFrame.getData(), clientToProxyFrame.isEndStream());
                    proxyToClientStream.data(proxyToServerFrame, this);
                    yield Action.SCHEDULED;
                }
                // TODO
                case PUSH_PROMISE -> throw new UnsupportedOperationException();
                default -> throw new IllegalStateException();
            };
        }

        @Override
        public void succeeded()
        {
            frameInfo.callback.succeeded();
            super.succeeded();
        }

        @Override
        public void failed(Throwable failure)
        {
            frameInfo.callback.failed(failure);
            super.failed(failure);
        }

        private void offer(Stream stream, Frame frame, Callback callback)
        {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("SPC queueing {} for {} on {}", frame, stream, stream.getSession());
            try (AutoLock ignored = lock.lock())
            {
                Deque<FrameInfo> deque = frames.computeIfAbsent(stream, s -> new ArrayDeque<>());
                deque.offer(new FrameInfo(frame, callback));
            }
            iterate();
        }

        @Override
        public void onHeaders(Stream stream, HeadersFrame frame)
        {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("SPC received {} on {}", frame, stream);
            offer(stream, frame, NOOP);
            stream.demand();
        }

        @Override
        public Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
        {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("SPC received {} on {}", frame, stream);
            // TODO
            return null;
        }

        @Override
        public void onDataAvailable(Stream stream)
        {
            Stream.Data data = stream.readData();
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("SPC read {} on {}", data, stream);
            offer(stream, data.frame(), Callback.from(data::release));
            if (!data.frame().isEndStream())
                stream.demand();
        }

        @Override
        public void onReset(Stream stream, ResetFrame frame, Callback callback)
        {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("SPC received {} on {}", frame, stream);
            // TODO: drain queue, reset client stream.
            callback.succeeded();
        }

        @Override
        public void onIdleTimeout(Stream stream, Throwable x, Promise<Boolean> promise)
        {
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("SPC idle timeout for {}", stream);
            // TODO:
            promise.succeeded(true);
        }

        private void link(Stream proxyToServerStream, Stream clientToProxyStream)
        {
            try (AutoLock ignored = lock.lock())
            {
                streams.put(proxyToServerStream, clientToProxyStream);
            }
            iterate();
        }
    }

    private static class FrameInfo
    {
        private final Frame frame;
        private final Callback callback;

        private FrameInfo(Frame frame, Callback callback)
        {
            this.frame = frame;
            this.callback = callback;
        }

        @Override
        public String toString()
        {
            return frame.toString();
        }
    }
}
