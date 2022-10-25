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

package org.eclipse.jetty.http.client;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.AbstractConnectionPool;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.HTTP2Cipher;
import org.eclipse.jetty.http2.HTTP2Connection;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.hpack.AuthorityHttpField;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.proxy.AsyncProxyServlet;
import org.eclipse.jetty.proxy.ConnectHandler;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProxyWithDynamicTransportTest
{
    private static final Logger LOG = LoggerFactory.getLogger(ProxyWithDynamicTransportTest.class);

    private Server server;
    private ServerConnector serverConnector;
    private ServerConnector serverTLSConnector;
    private Server proxy;
    private ServerConnector proxyConnector;
    private ServerConnector proxyTLSConnector;
    private HTTP2Client http2Client;
    private HttpClient client;

    private void start(Handler handler) throws Exception
    {
        startServer(handler);
        startProxy(new ConnectHandler());
        startClient();
    }

    private void startServer(Handler handler) throws Exception
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setUseCipherSuitesOrder(true);
        sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);

        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        HttpConfiguration httpConfig = new HttpConfiguration();
        HttpConnectionFactory h1c = new HttpConnectionFactory(httpConfig);
        HTTP2CServerConnectionFactory h2c = new HTTP2CServerConnectionFactory(httpConfig);
        serverConnector = new ServerConnector(server, 1, 1, h1c, h2c);
        server.addConnector(serverConnector);
        HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
        httpsConfig.addCustomizer(new SecureRequestCustomizer());
        HttpConnectionFactory h1 = new HttpConnectionFactory(httpsConfig);
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpsConfig);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h1.getProtocol());
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());
        serverTLSConnector = new ServerConnector(server, 1, 1, ssl, alpn, h2, h1, h2c);
        server.addConnector(serverTLSConnector);
        server.setHandler(handler);
        server.start();
        LOG.info("Started server on :{} and :{}", serverConnector.getLocalPort(), serverTLSConnector.getLocalPort());
    }

    private void startProxy(ConnectHandler connectHandler) throws Exception
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setUseCipherSuitesOrder(true);
        sslContextFactory.setCipherComparator(HTTP2Cipher.COMPARATOR);

        QueuedThreadPool proxyThreads = new QueuedThreadPool();
        proxyThreads.setName("proxy");
        proxy = new Server(proxyThreads);

        HttpConfiguration httpConfig = new HttpConfiguration();
        ConnectionFactory h1c = new HttpConnectionFactory(httpConfig);
        ConnectionFactory h2c = new HTTP2CServerConnectionFactory(httpConfig);
        proxyConnector = new ServerConnector(proxy, 1, 1, h1c, h2c);
        proxy.addConnector(proxyConnector);
        HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
        httpsConfig.addCustomizer(new SecureRequestCustomizer());
        HttpConnectionFactory h1 = new HttpConnectionFactory(httpsConfig);
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpsConfig);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h1.getProtocol());
        SslConnectionFactory ssl = new SslConnectionFactory(sslContextFactory, alpn.getProtocol());
        proxyTLSConnector = new ServerConnector(proxy, 1, 1, ssl, alpn, h2, h1, h2c);
        proxy.addConnector(proxyTLSConnector);

        proxy.setHandler(connectHandler);
        ServletContextHandler context = new ServletContextHandler(connectHandler, "/");
        ServletHolder holder = new ServletHolder(new AsyncProxyServlet()
        {
            @Override
            protected HttpClient newHttpClient(ClientConnector clientConnector)
            {
                ClientConnectionFactory.Info h1 = HttpClientConnectionFactory.HTTP11;
                HTTP2Client http2Client = new HTTP2Client(clientConnector);
                ClientConnectionFactory.Info http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);
                return new HttpClient(new HttpClientTransportDynamic(clientConnector, h1, http2));
            }
        });
        context.addServlet(holder, "/*");
        proxy.start();
        LOG.info("Started proxy on :{} and :{}", proxyConnector.getLocalPort(), proxyTLSConnector.getLocalPort());
    }

    private void startClient() throws Exception
    {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSelectors(1);
        clientConnector.setExecutor(clientThreads);
        clientConnector.setSslContextFactory(new SslContextFactory.Client(true));
        http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info h1 = HttpClientConnectionFactory.HTTP11;
        ClientConnectionFactory.Info http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);
        client = new HttpClient(new HttpClientTransportDynamic(clientConnector, h1, http2));
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (server != null)
            server.stop();
        if (proxy != null)
            proxy.stop();
        if (client != null)
            client.stop();
    }

    private static java.util.stream.Stream<Arguments> testParams()
    {
        var h1 = List.of("http/1.1");
        var h2c = List.of("h2c");
        var h2 = List.of("h2");
        return java.util.stream.Stream.of(
            // HTTP/1.1 Proxy with HTTP/1.1 Server.
            Arguments.of(new Origin.Protocol(h1, false), false, HttpVersion.HTTP_1_1, false),
            Arguments.of(new Origin.Protocol(h1, false), false, HttpVersion.HTTP_1_1, true),
            Arguments.of(new Origin.Protocol(h1, false), true, HttpVersion.HTTP_1_1, false),
            Arguments.of(new Origin.Protocol(h1, false), true, HttpVersion.HTTP_1_1, true),
            // HTTP/1.1 Proxy with HTTP/2 Server.
            Arguments.of(new Origin.Protocol(h1, false), false, HttpVersion.HTTP_2, false),
            Arguments.of(new Origin.Protocol(h1, false), false, HttpVersion.HTTP_2, true),
            Arguments.of(new Origin.Protocol(h1, false), true, HttpVersion.HTTP_2, false),
            Arguments.of(new Origin.Protocol(h1, false), true, HttpVersion.HTTP_2, true),
            // HTTP/2 Proxy with HTTP/1.1 Server.
            Arguments.of(new Origin.Protocol(h2c, false), false, HttpVersion.HTTP_1_1, false),
            Arguments.of(new Origin.Protocol(h2c, false), false, HttpVersion.HTTP_1_1, true),
            Arguments.of(new Origin.Protocol(h2, false), true, HttpVersion.HTTP_1_1, false),
            Arguments.of(new Origin.Protocol(h2, false), true, HttpVersion.HTTP_1_1, true),
            Arguments.of(new Origin.Protocol(h2, true), true, HttpVersion.HTTP_1_1, false),
            Arguments.of(new Origin.Protocol(h2, true), true, HttpVersion.HTTP_1_1, true),
            // HTTP/2 Proxy with HTTP/2 Server.
            Arguments.of(new Origin.Protocol(h2c, false), false, HttpVersion.HTTP_2, false),
            Arguments.of(new Origin.Protocol(h2c, false), false, HttpVersion.HTTP_2, true),
            Arguments.of(new Origin.Protocol(h2, false), true, HttpVersion.HTTP_2, false),
            Arguments.of(new Origin.Protocol(h2, false), true, HttpVersion.HTTP_2, true),
            Arguments.of(new Origin.Protocol(h2, true), true, HttpVersion.HTTP_2, false),
            Arguments.of(new Origin.Protocol(h2, true), true, HttpVersion.HTTP_2, true)
        );
    }

    @ParameterizedTest(name = "proxyProtocol={0}, proxySecure={1}, serverProtocol={2}, serverSecure={3}")
    @MethodSource("testParams")
    public void testProxy(Origin.Protocol proxyProtocol, boolean proxySecure, HttpVersion serverProtocol, boolean serverSecure) throws Exception
    {
        int status = HttpStatus.NO_CONTENT_204;
        start(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response)
            {
                response.setStatus(status);
            }
        });

        int proxyPort = proxySecure ? proxyTLSConnector.getLocalPort() : proxyConnector.getLocalPort();
        Origin.Address proxyAddress = new Origin.Address("localhost", proxyPort);
        HttpProxy proxy = new HttpProxy(proxyAddress, proxySecure, proxyProtocol);
        client.getProxyConfiguration().addProxy(proxy);

        String scheme = serverSecure ? "https" : "http";
        int serverPort = serverSecure ? serverTLSConnector.getLocalPort() : serverConnector.getLocalPort();
        ContentResponse response1 = client.newRequest("localhost", serverPort)
            .scheme(scheme)
            .version(serverProtocol)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(status, response1.getStatus());

        // Make a second request to be sure it went through the same connection.
        ContentResponse response2 = client.newRequest("localhost", serverPort)
            .scheme(scheme)
            .version(serverProtocol)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(status, response2.getStatus());

        List<Destination> destinations = client.getDestinations().stream()
            .filter(d -> d.getPort() == serverPort)
            .collect(Collectors.toList());
        assertEquals(1, destinations.size());
        HttpDestination destination = (HttpDestination)destinations.get(0);
        AbstractConnectionPool connectionPool = (AbstractConnectionPool)destination.getConnectionPool();
        assertEquals(1, connectionPool.getConnectionCount());
    }

    @Test
    public void testHTTP2TunnelClosedByClient() throws Exception
    {
        start(new EmptyServerHandler());

        int proxyPort = proxyConnector.getLocalPort();
        Origin.Address proxyAddress = new Origin.Address("localhost", proxyPort);
        HttpProxy proxy = new HttpProxy(proxyAddress, false, new Origin.Protocol(List.of("h2c"), false));
        client.getProxyConfiguration().addProxy(proxy);

        long idleTimeout = 1000;
        http2Client.setStreamIdleTimeout(idleTimeout);

        String serverScheme = "http";
        int serverPort = serverConnector.getLocalPort();
        ContentResponse response = client.newRequest("localhost", serverPort)
            .scheme(serverScheme)
            .version(HttpVersion.HTTP_1_1)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        // Client will close the HTTP2StreamEndPoint.
        Thread.sleep(2 * idleTimeout);

        List<Destination> destinations = client.getDestinations().stream()
            .filter(d -> d.getPort() == serverPort)
            .collect(Collectors.toList());
        assertEquals(1, destinations.size());
        HttpDestination destination = (HttpDestination)destinations.get(0);
        AbstractConnectionPool connectionPool = (AbstractConnectionPool)destination.getConnectionPool();
        assertEquals(0, connectionPool.getConnectionCount());

        List<HTTP2Connection> serverConnections = proxyConnector.getConnectedEndPoints().stream()
            .map(EndPoint::getConnection)
            .map(HTTP2Connection.class::cast)
            .collect(Collectors.toList());
        assertEquals(1, serverConnections.size());
        assertTrue(serverConnections.get(0).getSession().getStreams().isEmpty());
    }

    @Test
    public void testProxyDown() throws Exception
    {
        start(new EmptyServerHandler());

        int proxyPort = proxyConnector.getLocalPort();
        Origin.Address proxyAddress = new Origin.Address("localhost", proxyPort);
        HttpProxy httpProxy = new HttpProxy(proxyAddress, false, new Origin.Protocol(List.of("h2c"), false));
        client.getProxyConfiguration().addProxy(httpProxy);
        proxy.stop();

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", serverConnector.getLocalPort())
            .version(HttpVersion.HTTP_1_1)
            .timeout(5, TimeUnit.SECONDS)
            .send(result ->
            {
                assertTrue(result.isFailed());
                assertThat(result.getFailure(), Matchers.instanceOf(ConnectException.class));
                latch.countDown();
            });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testHTTP2TunnelHardClosedByProxy() throws Exception
    {
        startServer(new EmptyServerHandler());
        CountDownLatch closeLatch = new CountDownLatch(1);
        startProxy(new ConnectHandler()
        {
            @Override
            protected void handleConnect(Request jettyRequest, HttpServletRequest request, HttpServletResponse response, String serverAddress)
            {
                jettyRequest.getHttpChannel().getEndPoint().close();
                closeLatch.countDown();
            }
        });
        startClient();

        int proxyPort = proxyConnector.getLocalPort();
        Origin.Address proxyAddress = new Origin.Address("localhost", proxyPort);
        HttpProxy httpProxy = new HttpProxy(proxyAddress, false, new Origin.Protocol(List.of("h2c"), false));
        client.getProxyConfiguration().addProxy(httpProxy);

        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", serverConnector.getLocalPort())
            .version(HttpVersion.HTTP_1_1)
            .timeout(5, TimeUnit.SECONDS)
            .send(result ->
            {
                assertTrue(result.isFailed());
                assertThat(result.getFailure(), Matchers.instanceOf(ClosedChannelException.class));
                latch.countDown();
            });
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
        assertTrue(latch.await(5, TimeUnit.SECONDS));

        List<Destination> destinations = client.getDestinations().stream()
            .filter(d -> d.getPort() == proxyPort)
            .collect(Collectors.toList());
        assertEquals(1, destinations.size());
        HttpDestination destination = (HttpDestination)destinations.get(0);
        AbstractConnectionPool connectionPool = (AbstractConnectionPool)destination.getConnectionPool();
        assertEquals(0, connectionPool.getConnectionCount());
    }

    @Test
    public void testHTTP2TunnelResetByClient() throws Exception
    {
        startServer(new EmptyServerHandler());
        CountDownLatch closeLatch = new CountDownLatch(2);
        startProxy(new ConnectHandler()
        {
            @Override
            protected DownstreamConnection newDownstreamConnection(EndPoint endPoint, ConcurrentMap<String, Object> context)
            {
                return new DownstreamConnection(endPoint, getExecutor(), getByteBufferPool(), context)
                {
                    @Override
                    protected void close(Throwable failure)
                    {
                        super.close(failure);
                        closeLatch.countDown();
                    }
                };
            }

            @Override
            protected UpstreamConnection newUpstreamConnection(EndPoint endPoint, ConnectContext connectContext)
            {
                return new UpstreamConnection(endPoint, getExecutor(), getByteBufferPool(), connectContext)
                {
                    @Override
                    protected void close(Throwable failure)
                    {
                        super.close(failure);
                        closeLatch.countDown();
                    }
                };
            }
        });
        startClient();

        FuturePromise<Session> sessionPromise = new FuturePromise<>();
        http2Client.connect(new InetSocketAddress("localhost", proxyConnector.getLocalPort()), new Session.Listener.Adapter(), sessionPromise);
        Session session = sessionPromise.get(5, TimeUnit.SECONDS);
        String serverAddress = "localhost:" + serverConnector.getLocalPort();
        MetaData.ConnectRequest connect = new MetaData.ConnectRequest(HttpScheme.HTTP, new AuthorityHttpField(serverAddress), null, HttpFields.EMPTY, null);
        HeadersFrame frame = new HeadersFrame(connect, null, false);
        FuturePromise<Stream> streamPromise = new FuturePromise<>();
        CountDownLatch tunnelLatch = new CountDownLatch(1);
        CountDownLatch responseLatch = new CountDownLatch(1);
        session.newStream(frame, streamPromise, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                if (response.getStatus() == HttpStatus.OK_200)
                    tunnelLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                ByteBuffer data = frame.getData();
                String response = BufferUtil.toString(data, StandardCharsets.UTF_8);
                if (response.startsWith("HTTP/1.1 200"))
                    responseLatch.countDown();
            }
        });
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);
        assertTrue(tunnelLatch.await(5, TimeUnit.SECONDS));

        // Tunnel is established, send a HTTP/1.1 request.
        String h1 = "GET / HTTP/1.1\r\n" +
            "Host: " + serverAddress + "\r\n" +
            "\r\n";
        stream.data(new DataFrame(stream.getId(), ByteBuffer.wrap(h1.getBytes(StandardCharsets.UTF_8)), false), Callback.NOOP);
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));

        // Now reset the stream, tunnel must be closed.
        stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testHTTP2TunnelProxyStreamTimeout() throws Exception
    {
        startServer(new EmptyServerHandler());
        CountDownLatch closeLatch = new CountDownLatch(2);
        startProxy(new ConnectHandler()
        {
            @Override
            protected DownstreamConnection newDownstreamConnection(EndPoint endPoint, ConcurrentMap<String, Object> context)
            {
                return new DownstreamConnection(endPoint, getExecutor(), getByteBufferPool(), context)
                {
                    @Override
                    protected void close(Throwable failure)
                    {
                        super.close(failure);
                        closeLatch.countDown();
                    }
                };
            }

            @Override
            protected UpstreamConnection newUpstreamConnection(EndPoint endPoint, ConnectContext connectContext)
            {
                return new UpstreamConnection(endPoint, getExecutor(), getByteBufferPool(), connectContext)
                {
                    @Override
                    protected void close(Throwable failure)
                    {
                        super.close(failure);
                        closeLatch.countDown();
                    }
                };
            }
        });
        startClient();

        long streamIdleTimeout = 1000;
        ConnectionFactory h2c = proxyConnector.getConnectionFactory("h2c");
        ((HTTP2CServerConnectionFactory)h2c).setStreamIdleTimeout(streamIdleTimeout);

        FuturePromise<Session> sessionPromise = new FuturePromise<>();
        http2Client.connect(new InetSocketAddress("localhost", proxyConnector.getLocalPort()), new Session.Listener.Adapter(), sessionPromise);
        Session session = sessionPromise.get(5, TimeUnit.SECONDS);
        String serverAddress = "localhost:" + serverConnector.getLocalPort();
        MetaData.ConnectRequest connect = new MetaData.ConnectRequest(HttpScheme.HTTP, new AuthorityHttpField(serverAddress), null, HttpFields.EMPTY, null);
        HeadersFrame frame = new HeadersFrame(connect, null, false);
        FuturePromise<Stream> streamPromise = new FuturePromise<>();
        CountDownLatch tunnelLatch = new CountDownLatch(1);
        CountDownLatch responseLatch = new CountDownLatch(1);
        CountDownLatch resetLatch = new CountDownLatch(1);
        session.newStream(frame, streamPromise, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                if (response.getStatus() == HttpStatus.OK_200)
                    tunnelLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                ByteBuffer data = frame.getData();
                String response = BufferUtil.toString(data, StandardCharsets.UTF_8);
                if (response.startsWith("HTTP/1.1 200"))
                    responseLatch.countDown();
            }

            @Override
            public void onReset(Stream stream, ResetFrame frame)
            {
                resetLatch.countDown();
            }
        });
        Stream stream = streamPromise.get(5, TimeUnit.SECONDS);
        assertTrue(tunnelLatch.await(5, TimeUnit.SECONDS));

        // Tunnel is established, send a HTTP/1.1 request.
        String h1 = "GET / HTTP/1.1\r\n" +
            "Host: " + serverAddress + "\r\n" +
            "\r\n";
        stream.data(new DataFrame(stream.getId(), ByteBuffer.wrap(h1.getBytes(StandardCharsets.UTF_8)), false), Callback.NOOP);
        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));

        // Wait until the proxy stream idle times out.
        Thread.sleep(2 * streamIdleTimeout);

        // Client should see a RST_STREAM.
        assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
        // Tunnel must be closed.
        assertTrue(closeLatch.await(5, TimeUnit.SECONDS));
    }
}
