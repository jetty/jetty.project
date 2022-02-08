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

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.ProxyConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProxyProtocolTest
{
    private Server server;
    private ServerConnector connector;
    private HTTP2Client client;

    public void startServer(Handler handler) throws Exception
    {
        server = new Server();
        HttpConfiguration configuration = new HttpConfiguration();
        connector = new ServerConnector(server, new ProxyConnectionFactory(), new HTTP2CServerConnectionFactory(configuration));
        server.addConnector(connector);
        server.setHandler(handler);

        client = new HTTP2Client();
        server.addBean(client, true);

        server.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (server != null)
            server.stop();
    }

    @Test
    public void testProxyGetV1() throws Exception
    {
        startServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    assertEquals("1.2.3.4", request.getRemoteAddr());
                    assertEquals(1111, request.getRemotePort());
                    assertEquals("5.6.7.8", request.getLocalAddr());
                    assertEquals(2222, request.getLocalPort());
                }
                catch (Throwable th)
                {
                    th.printStackTrace();
                    response.setStatus(500);
                }
                baseRequest.setHandled(true);
            }
        });

        String request1 = "PROXY TCP4 1.2.3.4 5.6.7.8 1111 2222\r\n";
        SocketChannel channel = SocketChannel.open();
        channel.connect(new InetSocketAddress("localhost", connector.getLocalPort()));
        channel.write(ByteBuffer.wrap(request1.getBytes(StandardCharsets.UTF_8)));

        FuturePromise<Session> promise = new FuturePromise<>();
        client.accept(null, channel, new Session.Listener.Adapter(), promise);
        Session session = promise.get(5, TimeUnit.SECONDS);

        String uri = "http://localhost:" + connector.getLocalPort() + "/";
        MetaData.Request metaData = new MetaData.Request("GET", HttpURI.from(uri), HttpVersion.HTTP_2, HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(HttpStatus.OK_200, response.getStatus());
                if (frame.isEndStream())
                    latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testProxyGetV2() throws Exception
    {
        startServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                try
                {
                    assertEquals("10.0.0.4", request.getRemoteAddr());
                    assertEquals(33824, request.getRemotePort());
                    assertEquals("10.0.0.5", request.getLocalAddr());
                    assertEquals(8888, request.getLocalPort());
                    EndPoint endPoint = baseRequest.getHttpChannel().getEndPoint();
                    assertThat(endPoint, instanceOf(ProxyConnectionFactory.ProxyEndPoint.class));
                    ProxyConnectionFactory.ProxyEndPoint proxyEndPoint = (ProxyConnectionFactory.ProxyEndPoint)endPoint;
                    assertNotNull(proxyEndPoint.getAttribute(ProxyConnectionFactory.TLS_VERSION));
                }
                catch (Throwable th)
                {
                    th.printStackTrace();
                    response.setStatus(500);
                }
                baseRequest.setHandled(true);
            }
        });

        // String is: "MAGIC VER|CMD FAM|PROT LEN SRC_ADDR DST_ADDR SRC_PORT DST_PORT PP2_TYPE_SSL LEN CLIENT VERIFY PP2_SUBTYPE_SSL_VERSION LEN 1.2"
        String request1 = "0D0A0D0A000D0A515549540A 21 11 001A 0A000004 0A000005 8420 22B8 20 000B 01 00000000 21 0003 312E32";
        request1 = StringUtil.strip(request1, " ");
        SocketChannel channel = SocketChannel.open();
        channel.connect(new InetSocketAddress("localhost", connector.getLocalPort()));
        channel.write(ByteBuffer.wrap(TypeUtil.fromHexString(request1)));

        FuturePromise<Session> promise = new FuturePromise<>();
        client.accept(null, channel, new Session.Listener.Adapter(), promise);
        Session session = promise.get(5, TimeUnit.SECONDS);

        String uri = "http://localhost:" + connector.getLocalPort() + "/";
        MetaData.Request metaData = new MetaData.Request("GET", HttpURI.from(uri), HttpVersion.HTTP_2, HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertEquals(HttpStatus.OK_200, response.getStatus());
                if (frame.isEndStream())
                    latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
}
