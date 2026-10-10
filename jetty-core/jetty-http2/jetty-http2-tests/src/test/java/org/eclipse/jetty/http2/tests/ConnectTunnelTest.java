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

package org.eclipse.jetty.http2.tests;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Stream;
import org.eclipse.jetty.http2.HTTP2StreamEndPoint;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.server.internal.ServerHTTP2StreamEndPoint;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConnectTunnelTest extends AbstractTest
{
    @Test
    public void testCONNECT() throws Exception
    {
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                // Verifies that the CONNECT request is well formed.
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                assertEquals(HttpMethod.CONNECT.asString(), request.getMethod());
                HttpURI uri = request.getHttpURI();
                assertNull(uri.getScheme());
                assertNull(uri.getPath());
                assertNotNull(uri.getAuthority());
                MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, false), Callback.from(stream::demand));
                return new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        Stream.Data data = stream.readData();
                        stream.data(data.frame(), Callback.from(data::release));
                    }
                };
            }
        });

        Session client = newClientSession(new Session.Listener() {});

        CountDownLatch latch = new CountDownLatch(1);
        byte[] bytes = "HELLO".getBytes(StandardCharsets.UTF_8);
        String host = "localhost";
        int port = connector.getLocalPort();
        String authority = host + ":" + port;
        MetaData.Request request = new MetaData.Request(HttpMethod.CONNECT.asString(), null, new HostPortHttpField(authority), null, HttpVersion.HTTP_2, HttpFields.EMPTY, -1);
        FuturePromise<Stream> streamPromise = new FuturePromise<>();
        client.newStream(new HeadersFrame(request, null, false), streamPromise, new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                data.release();
                if (data.frame().isEndStream())
                    latch.countDown();
            }
        });
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);
        ByteBuffer data = ByteBuffer.wrap(bytes);
        stream.data(new DataFrame(stream.getId(), data, true), Callback.NOOP);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testCONNECTWithProtocol() throws Exception
    {
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                // Verifies that the CONNECT request is well formed.
                MetaData.Request request = (MetaData.Request)frame.getMetaData();
                assertEquals(HttpMethod.CONNECT.asString(), request.getMethod());
                HttpURI uri = request.getHttpURI();
                assertNotNull(uri.getScheme());
                assertNotNull(uri.getPath());
                assertNotNull(uri.getAuthority());
                assertNotNull(request.getProtocol());
                MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, false), Callback.from(stream::demand));
                return new Stream.Listener()
                {
                    @Override
                    public void onDataAvailable(Stream stream)
                    {
                        Stream.Data data = stream.readData();
                        stream.data(data.frame(), Callback.from(data::release));
                    }
                };
            }
        });

        Session client = newClientSession(new Session.Listener() {});

        CountDownLatch latch = new CountDownLatch(1);
        byte[] bytes = "HELLO".getBytes(StandardCharsets.UTF_8);
        String host = "localhost";
        int port = connector.getLocalPort();
        String authority = host + ":" + port;
        MetaData.Request request = new MetaData.ConnectRequest(HttpScheme.HTTP, new HostPortHttpField(authority), "/", HttpFields.EMPTY, "websocket");
        FuturePromise<Stream> streamPromise = new FuturePromise<>();
        client.newStream(new HeadersFrame(request, null, false), streamPromise, new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                data.release();
                if (data.frame().isEndStream())
                    latch.countDown();
            }
        });
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);
        ByteBuffer data = ByteBuffer.wrap(bytes);
        stream.data(new DataFrame(stream.getId(), data, true), Callback.NOOP);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testResetBeforeUpgrade() throws Exception
    {
        AtomicReference<HTTP2StreamEndPoint> endPointRef = new AtomicReference<>();
        CountDownLatch serverResetLatch = new CountDownLatch(1);
        CountDownLatch endPointCloseLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                ServerHTTP2StreamEndPoint endPoint = new ServerHTTP2StreamEndPoint((HTTP2Stream)stream)
                {
                    @Override
                    public void onClose(Throwable cause)
                    {
                        super.onClose(cause);
                        endPointCloseLatch.countDown();
                    }
                };
                endPointRef.set(endPoint);
                MetaData.Response response = new MetaData.Response(HttpStatus.OK_200, null, HttpVersion.HTTP_2, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, false), Callback.NOOP);
                return new Stream.Listener()
                {
                    @Override
                    public void onReset(Stream stream, ResetFrame frame, Callback callback)
                    {
                        // Simulates the reset arriving before HttpStreamOverHTTP2 upgrades the EndPoint.
                        endPoint.onFailure(new EofException(), callback).run();
                        serverResetLatch.countDown();
                    }
                };
            }
        });

        Session client = newClientSession(new Session.Listener() {});

        String authority = "localhost:" + connector.getLocalPort();
        MetaData.Request request = new MetaData.Request(HttpMethod.CONNECT.asString(), null, new HostPortHttpField(authority), null, HttpVersion.HTTP_2, HttpFields.EMPTY, -1);
        client.newStream(new HeadersFrame(request, null, false), new FuturePromise<>(), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
            }
        });

        assertTrue(serverResetLatch.await(5, TimeUnit.SECONDS));
        HTTP2StreamEndPoint endPoint = endPointRef.get();
        assertTrue(endPointCloseLatch.await(5, TimeUnit.SECONDS));
        assertFalse(endPoint.isOpen());

        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        AbstractConnection connection = new AbstractConnection(endPoint, connector.getExecutor())
        {
            @Override
            public void onFillable()
            {
            }
        };
        connection.addEventListener(new Connection.Listener()
        {
            @Override
            public void onOpened(Connection connection)
            {
                openLatch.countDown();
            }

            @Override
            public void onClosed(Connection connection)
            {
                closeLatch.countDown();
            }
        });
        endPoint.upgrade(connection);

        assertTrue(openLatch.await(5, TimeUnit.SECONDS));
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }
}
