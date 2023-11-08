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

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.ProxyConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.StringUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
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
        configuration.addCustomizer(new SecureRequestCustomizer());
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
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                assertEquals("1.2.3.4", Request.getRemoteAddr(request));
                assertEquals(1111, Request.getRemotePort(request));
                assertEquals("5.6.7.8", Request.getLocalAddr(request));
                assertEquals(2222, Request.getLocalPort(request));
                callback.succeeded();
                return true;
            }
        });

        String request1 = "PROXY TCP4 1.2.3.4 5.6.7.8 1111 2222\r\n";
        SocketChannel channel = SocketChannel.open();
        channel.connect(new InetSocketAddress("localhost", connector.getLocalPort()));
        channel.write(ByteBuffer.wrap(request1.getBytes(StandardCharsets.UTF_8)));

        FuturePromise<Session> promise = new FuturePromise<>();
        client.accept(null, channel, new Session.Listener() {}, promise);
        Session session = promise.get(5, TimeUnit.SECONDS);

        String uri = "http://localhost:" + connector.getLocalPort() + "/";
        MetaData.Request metaData = new MetaData.Request("GET", HttpURI.from(uri), HttpVersion.HTTP_2, HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener()
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
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                assertTrue(request.isSecure());
                assertTrue(request.getConnectionMetaData().isSecure());
                assertEquals("10.0.0.4", Request.getRemoteAddr(request));
                assertEquals(33824, Request.getRemotePort(request));
                assertEquals("10.0.0.5", Request.getLocalAddr(request));
                assertEquals(8888, Request.getLocalPort(request));
                EndPoint endPoint = request.getConnectionMetaData().getConnection().getEndPoint();
                assertThat(endPoint, instanceOf(ProxyConnectionFactory.ProxyEndPoint.class));
                ProxyConnectionFactory.ProxyEndPoint proxyEndPoint = (ProxyConnectionFactory.ProxyEndPoint)endPoint;
                assertNotNull(proxyEndPoint.getSslSessionData());
                callback.succeeded();
                return true;
            }
        });

        // String is: "MAGIC VER|CMD FAM|PROT LEN SRC_ADDR DST_ADDR SRC_PORT DST_PORT PP2_TYPE_SSL LEN CLIENT VERIFY PP2_SUBTYPE_SSL_VERSION LEN 1.2"
        String request1 = "0D0A0D0A000D0A515549540A 21 11 001A 0A000004 0A000005 8420 22B8 20 000B 01 00000000 21 0003 312E32";
        request1 = StringUtil.strip(request1, " ");
        SocketChannel channel = SocketChannel.open();
        channel.connect(new InetSocketAddress("localhost", connector.getLocalPort()));
        channel.write(ByteBuffer.wrap(StringUtil.fromHexString(request1)));

        FuturePromise<Session> promise = new FuturePromise<>();
        client.accept(null, channel, new Session.Listener() {}, promise);
        Session session = promise.get(5, TimeUnit.SECONDS);

        String uri = "http://localhost:" + connector.getLocalPort() + "/";
        MetaData.Request metaData = new MetaData.Request("GET", HttpURI.from(uri), HttpVersion.HTTP_2, HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener()
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
    public void testProxyGetV2Cipher() throws Exception
    {
        startServer(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                assertTrue(request.isSecure());
                assertTrue(request.getConnectionMetaData().isSecure());
                assertEquals("10.0.0.4", Request.getRemoteAddr(request));
                assertEquals(33824, Request.getRemotePort(request));
                assertEquals("10.0.0.5", Request.getLocalAddr(request));
                assertEquals(8888, Request.getLocalPort(request));
                EndPoint endPoint = request.getConnectionMetaData().getConnection().getEndPoint();
                assertThat(endPoint, instanceOf(ProxyConnectionFactory.ProxyEndPoint.class));
                ProxyConnectionFactory.ProxyEndPoint proxyEndPoint = (ProxyConnectionFactory.ProxyEndPoint)endPoint;
                EndPoint.SslSessionData sslSessionData = proxyEndPoint.getSslSessionData();
                assertThat(sslSessionData, notNullValue());
                assertThat(sslSessionData.cipherSuite(), equalTo("TEST_128_XYZ"));
                assertThat(sslSessionData.sslSessionId(), equalTo(StringUtil.toHexString("FooBar".getBytes(StandardCharsets.US_ASCII))));
                assertThat(request.getAttribute(EndPoint.SslSessionData.ATTRIBUTE), sameInstance(sslSessionData));
                callback.succeeded();
                return true;
            }
        });

        String request1 =
            "0D0A0D0A000D0A515549540A" + // MAGIC
            "21" +          // Version | Command = PROXY
            "11" +          // FAM = AF_INET | PROT = STREAM
            "0032" +        // length = 4+4+2+2+1+2+26+9
            "0A000004" +    // SRC_ADDR 10.0.0.4
            "0A000005" +    // DST_ADDR 10.0.0.5
            "8420" +        // SRC_PORT 33824
            "22B8" +        // DST_PORT 8888
            "20" +          // Type PP2_TYPE_SSL
            "001A" +        // length 26 = 1+4+1+2+3+1+2+12
            "01" +          // client PP2_CLIENT_SSL
            "00000000" +    // verify 0 == verified
            "21" +          // PP2_SUBTYPE_SSL_VERSION
            "0003" +        // len = 3
            "312E32" +      // version string "1.2"
            "23" +          // type PP2_SUBTYPE_SSL_CIPHER
            "000C" +        // length = 12
            "544553545F3132385F58595A" + // cipher "TEST_128_XYZ"
            "05" +          // type PP2_TYPE_UNIQUE_ID
            "0006" +        // length = 6
            "466f6f426172"  // value "FooBar" in hex
            ;

        SocketChannel channel = SocketChannel.open();
        channel.connect(new InetSocketAddress("localhost", connector.getLocalPort()));
        channel.write(ByteBuffer.wrap(StringUtil.fromHexString(request1)));

        FuturePromise<Session> promise = new FuturePromise<>();
        client.accept(null, channel, new Session.Listener() {}, promise);
        Session session = promise.get(5, TimeUnit.SECONDS);

        String uri = "http://localhost:" + connector.getLocalPort() + "/";
        MetaData.Request metaData = new MetaData.Request("GET", HttpURI.from(uri), HttpVersion.HTTP_2, HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(frame, new Promise.Adapter<>(), new Stream.Listener()
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
