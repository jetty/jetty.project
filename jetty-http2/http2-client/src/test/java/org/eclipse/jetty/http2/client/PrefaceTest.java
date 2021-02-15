//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2.client;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PrefaceTest extends AbstractTest
{
    @Test
    public void testServerPrefaceReplySentAfterClientPreface() throws Exception
    {
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public void onAccept(Session session)
            {
                // Send the server preface from here.
                session.settings(new SettingsFrame(new HashMap<>(), false), Callback.NOOP);
            }

            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, new HttpFields());
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(responseFrame, Callback.NOOP);
                return null;
            }
        });

        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                try
                {
                    // Wait for the server preface (a SETTINGS frame) to
                    // arrive on the client, and for its reply to be sent.
                    Thread.sleep(1000);
                    return null;
                }
                catch (InterruptedException x)
                {
                    x.printStackTrace();
                    return null;
                }
            }
        });

        CountDownLatch latch = new CountDownLatch(1);
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    latch.countDown();
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testClientPrefaceReplySentAfterServerPreface() throws Exception
    {
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                Map<Integer, Integer> settings = new HashMap<>();
                settings.put(SettingsFrame.MAX_CONCURRENT_STREAMS, 128);
                return settings;
            }

            @Override
            public void onPing(Session session, PingFrame frame)
            {
                session.close(ErrorCode.NO_ERROR.code, null, Callback.NOOP);
            }
        });

        ByteBufferPool byteBufferPool = client.getByteBufferPool();
        try (SocketChannel socket = SocketChannel.open())
        {
            socket.connect(new InetSocketAddress("localhost", connector.getLocalPort()));

            Generator generator = new Generator(byteBufferPool);
            ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
            generator.control(lease, new PrefaceFrame());
            Map<Integer, Integer> clientSettings = new HashMap<>();
            clientSettings.put(SettingsFrame.ENABLE_PUSH, 0);
            generator.control(lease, new SettingsFrame(clientSettings, false));
            // The PING frame just to make sure the client stops reading.
            generator.control(lease, new PingFrame(true));

            List<ByteBuffer> buffers = lease.getByteBuffers();
            socket.write(buffers.toArray(new ByteBuffer[buffers.size()]));

            Queue<SettingsFrame> settings = new ArrayDeque<>();
            Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
            {
                @Override
                public void onSettings(SettingsFrame frame)
                {
                    settings.offer(frame);
                }
            }, 4096, 8192);
            parser.init(UnaryOperator.identity());

            ByteBuffer buffer = byteBufferPool.acquire(1024, true);
            while (true)
            {
                BufferUtil.clearToFill(buffer);
                int read = socket.read(buffer);
                BufferUtil.flipToFlush(buffer, 0);
                if (read < 0)
                    break;
                parser.parse(buffer);
            }

            assertEquals(2, settings.size());
            SettingsFrame frame1 = settings.poll();
            assertFalse(frame1.isReply());
            SettingsFrame frame2 = settings.poll();
            assertTrue(frame2.isReply());
        }
    }

    @Test
    public void testOnPrefaceNotifiedForStandardUpgrade() throws Exception
    {
        Integer maxConcurrentStreams = 128;
        AtomicReference<CountDownLatch> serverPrefaceLatch = new AtomicReference<>(new CountDownLatch(1));
        AtomicReference<CountDownLatch> serverSettingsLatch = new AtomicReference<>(new CountDownLatch(1));
        HttpConfiguration config = new HttpConfiguration();
        prepareServer(new HttpConnectionFactory(config), new HTTP2CServerConnectionFactory(config)
        {
            @Override
            protected ServerSessionListener newSessionListener(Connector connector, EndPoint endPoint)
            {
                return new ServerSessionListener.Adapter()
                {
                    @Override
                    public Map<Integer, Integer> onPreface(Session session)
                    {
                        Map<Integer, Integer> serverSettings = new HashMap<>();
                        serverSettings.put(SettingsFrame.MAX_CONCURRENT_STREAMS, maxConcurrentStreams);
                        serverPrefaceLatch.get().countDown();
                        return serverSettings;
                    }

                    @Override
                    public void onSettings(Session session, SettingsFrame frame)
                    {
                        serverSettingsLatch.get().countDown();
                    }

                    @Override
                    public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
                    {
                        MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, new HttpFields());
                        stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                        return null;
                    }
                };
            }
        });
        server.start();

        ByteBufferPool byteBufferPool = new MappedByteBufferPool();
        try (SocketChannel socket = SocketChannel.open())
        {
            socket.connect(new InetSocketAddress("localhost", connector.getLocalPort()));

            String upgradeRequest =
                "GET /one HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Connection: Upgrade, HTTP2-Settings\r\n" +
                    "Upgrade: h2c\r\n" +
                    "HTTP2-Settings: \r\n" +
                    "\r\n";
            ByteBuffer upgradeBuffer = ByteBuffer.wrap(upgradeRequest.getBytes(StandardCharsets.ISO_8859_1));
            socket.write(upgradeBuffer);

            // Make sure onPreface() is called on server.
            assertTrue(serverPrefaceLatch.get().await(5, TimeUnit.SECONDS));
            assertTrue(serverSettingsLatch.get().await(5, TimeUnit.SECONDS));

            // The 101 response is the reply to the client preface SETTINGS frame.
            ByteBuffer buffer = byteBufferPool.acquire(1024, true);
            http1:
            while (true)
            {
                BufferUtil.clearToFill(buffer);
                int read = socket.read(buffer);
                BufferUtil.flipToFlush(buffer, 0);
                assertThat(read, greaterThanOrEqualTo(0));

                int crlfs = 0;
                while (buffer.hasRemaining())
                {
                    byte b = buffer.get();
                    if (b == '\r' || b == '\n')
                        ++crlfs;
                    else
                        crlfs = 0;
                    if (crlfs == 4)
                        break http1;
                }
            }

            // Reset the latches on server.
            serverPrefaceLatch.set(new CountDownLatch(1));
            serverSettingsLatch.set(new CountDownLatch(1));

            // After the 101, the client must send the connection preface.
            Generator generator = new Generator(byteBufferPool);
            ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
            generator.control(lease, new PrefaceFrame());
            Map<Integer, Integer> clientSettings = new HashMap<>();
            clientSettings.put(SettingsFrame.ENABLE_PUSH, 1);
            generator.control(lease, new SettingsFrame(clientSettings, false));
            List<ByteBuffer> buffers = lease.getByteBuffers();
            socket.write(buffers.toArray(new ByteBuffer[buffers.size()]));

            // However, we should not call onPreface() again.
            assertFalse(serverPrefaceLatch.get().await(1, TimeUnit.SECONDS));
            // Although we should notify of the SETTINGS frame.
            assertTrue(serverSettingsLatch.get().await(5, TimeUnit.SECONDS));

            CountDownLatch clientSettingsLatch = new CountDownLatch(1);
            AtomicBoolean responded = new AtomicBoolean();
            Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
            {
                @Override
                public void onSettings(SettingsFrame frame)
                {
                    if (frame.isReply())
                        return;
                    assertEquals(maxConcurrentStreams, frame.getSettings().get(SettingsFrame.MAX_CONCURRENT_STREAMS));
                    clientSettingsLatch.countDown();
                }

                @Override
                public void onHeaders(HeadersFrame frame)
                {
                    if (frame.isEndStream())
                        responded.set(true);
                }
            }, 4096, 8192);
            parser.init(UnaryOperator.identity());

            // HTTP/2 parsing.
            while (true)
            {
                parser.parse(buffer);
                if (responded.get())
                    break;

                BufferUtil.clearToFill(buffer);
                int read = socket.read(buffer);
                BufferUtil.flipToFlush(buffer, 0);
                assertThat(read, greaterThanOrEqualTo(0));
            }

            assertTrue(clientSettingsLatch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testInvalidServerPreface() throws Exception
    {
        try (ServerSocket server = new ServerSocket(0))
        {
            prepareClient();
            client.start();

            CountDownLatch failureLatch = new CountDownLatch(1);
            Promise.Completable<Session> promise = new Promise.Completable<>();
            InetSocketAddress address = new InetSocketAddress("localhost", server.getLocalPort());
            client.connect(address, new Session.Listener.Adapter()
            {
                @Override
                public void onFailure(Session session, Throwable failure)
                {
                    failureLatch.countDown();
                }
            }, promise);

            try (Socket socket = server.accept())
            {
                OutputStream output = socket.getOutputStream();
                output.write("enough_junk_bytes".getBytes(StandardCharsets.UTF_8));

                Session session = promise.get(5, TimeUnit.SECONDS);
                assertNotNull(session);

                assertTrue(failureLatch.await(5, TimeUnit.SECONDS));

                // Verify that the client closed the socket.
                InputStream input = socket.getInputStream();
                while (true)
                {
                    int read = input.read();
                    if (read < 0)
                        break;
                }
            }
        }
    }

    @Test
    public void testInvalidClientPreface() throws Exception
    {
        start(new ServerSessionListener.Adapter());

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            output.write("enough_junk_bytes".getBytes(StandardCharsets.UTF_8));
            output.flush();

            byte[] bytes = new byte[1024];
            InputStream input = client.getInputStream();
            int read = input.read(bytes);
            if (read < 0)
            {
                // Closing the connection without GOAWAY frame is fine.
                return;
            }

            int type = bytes[3];
            assertEquals(FrameType.GO_AWAY.getType(), type);
        }
    }
}
