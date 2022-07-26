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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                HttpURI uri = request.getURI();
                assertNull(uri.getScheme());
                assertNull(uri.getPath());
                assertNotNull(uri.getAuthority());
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
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
                HttpURI uri = request.getURI();
                assertNotNull(uri.getScheme());
                assertNotNull(uri.getPath());
                assertNotNull(uri.getAuthority());
                assertNotNull(request.getProtocol());
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, HttpStatus.OK_200, HttpFields.EMPTY);
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
}
