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

package org.eclipse.jetty.http2.server;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CloseTest extends AbstractServerTest
{
    @Test
    public void testClientAbruptlyClosesConnection() throws Exception
    {
        final CountDownLatch closeLatch = new CountDownLatch(1);
        final AtomicReference<Session> sessionRef = new AtomicReference<>();
        startServer(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                try
                {
                    sessionRef.set(stream.getSession());
                    MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                    // Reply with HEADERS.
                    stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                    closeLatch.await(5, TimeUnit.SECONDS);
                    return null;
                }
                catch (InterruptedException x)
                {
                    return null;
                }
            }
        });

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.control(lease, new PrefaceFrame());
        generator.control(lease, new SettingsFrame(new HashMap<>(), false));
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        generator.control(lease, new HeadersFrame(1, metaData, null, true));

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                output.write(BufferUtil.toArray(buffer));
            }

            Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
            {
                @Override
                public void onHeaders(HeadersFrame frame)
                {
                    try
                    {
                        // Close the connection just after
                        // receiving the response headers.
                        client.close();
                        closeLatch.countDown();
                    }
                    catch (IOException x)
                    {
                        throw new RuntimeIOException(x);
                    }
                }
            }, 4096, 8192);
            parser.init(UnaryOperator.identity());

            parseResponse(client, parser);

            // We need to give some time to the server to receive and process the TCP FIN.
            Thread.sleep(1000);

            Session session = sessionRef.get();
            assertTrue(session.isClosed());
            assertTrue(((HTTP2Session)session).isDisconnected());
        }
    }

    @Test
    public void testClientSendsGoAwayButDoesNotCloseConnectionServerCloses() throws Exception
    {
        final AtomicReference<Session> sessionRef = new AtomicReference<>();
        startServer(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                sessionRef.set(stream.getSession());
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                return null;
            }
        });

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.control(lease, new PrefaceFrame());
        generator.control(lease, new SettingsFrame(new HashMap<>(), false));
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        generator.control(lease, new HeadersFrame(1, metaData, null, true));
        generator.control(lease, new GoAwayFrame(1, ErrorCode.NO_ERROR.code, "OK".getBytes("UTF-8")));

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                output.write(BufferUtil.toArray(buffer));
            }

            // Don't close the connection; the server should close.

            final CountDownLatch responseLatch = new CountDownLatch(1);
            Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
            {
                @Override
                public void onHeaders(HeadersFrame frame)
                {
                    // Even if we sent the GO_AWAY immediately after the
                    // HEADERS, the server is able to send us the response.
                    responseLatch.countDown();
                }
            }, 4096, 8192);
            parser.init(UnaryOperator.identity());

            parseResponse(client, parser);

            assertTrue(responseLatch.await(5, TimeUnit.SECONDS));

            // Wait for the server to close.
            Thread.sleep(1000);

            // Client received the TCP FIN from server.
            assertEquals(-1, client.getInputStream().read());

            // Server is closed.
            Session session = sessionRef.get();
            assertTrue(session.isClosed());
            assertTrue(((HTTP2Session)session).isDisconnected());
        }
    }

    @Test
    public void testServerSendsGoAwayClientDoesNotCloseServerIdleTimeout() throws Exception
    {
        final long idleTimeout = 1000;
        final AtomicReference<Session> sessionRef = new AtomicReference<>();
        startServer(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                sessionRef.set(stream.getSession());
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                stream.getSession().close(ErrorCode.NO_ERROR.code, "OK", Callback.NOOP);
                return null;
            }
        });
        connector.setIdleTimeout(idleTimeout);

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.control(lease, new PrefaceFrame());
        generator.control(lease, new SettingsFrame(new HashMap<>(), false));
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        generator.control(lease, new HeadersFrame(1, metaData, null, true));

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                output.write(BufferUtil.toArray(buffer));
            }

            final CountDownLatch responseLatch = new CountDownLatch(1);
            final CountDownLatch closeLatch = new CountDownLatch(1);
            Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
            {
                @Override
                public void onHeaders(HeadersFrame frame)
                {
                    responseLatch.countDown();
                }

                @Override
                public void onGoAway(GoAwayFrame frame)
                {
                    closeLatch.countDown();
                }
            }, 4096, 8192);
            parser.init(UnaryOperator.identity());

            parseResponse(client, parser);

            assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
            assertTrue(closeLatch.await(5, TimeUnit.SECONDS));

            // Don't close the connection.

            // Wait for the server to idle timeout.
            Thread.sleep(2 * idleTimeout);

            // Client received the TCP FIN from server.
            assertEquals(-1, client.getInputStream().read());

            // Server is closed.
            Session session = sessionRef.get();
            assertTrue(session.isClosed());
            assertTrue(((HTTP2Session)session).isDisconnected());
        }
    }
}
