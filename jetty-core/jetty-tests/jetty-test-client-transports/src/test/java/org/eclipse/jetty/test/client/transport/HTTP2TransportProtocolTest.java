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

package org.eclipse.jetty.test.client.transport;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.HttpClientTransportOverHTTP2;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.TransportProtocol;
import org.eclipse.jetty.quic.client.ClientQuicConfiguration;
import org.eclipse.jetty.quic.client.QuicTransportProtocol;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.quic.server.ServerQuicConfiguration;
import org.eclipse.jetty.server.MemoryConnector;
import org.eclipse.jetty.server.MemoryTransportProtocol;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.unixdomain.server.UnixDomainServerConnector;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class HTTP2TransportProtocolTest
{
    private Server server;
    private HttpClient httpClient;
    private HTTP2Client http2Client;

    @BeforeEach
    public void prepare()
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);

        ClientConnector clientConnector = new ClientConnector();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        serverThreads.setName("client");
        clientConnector.setExecutor(clientThreads);
        clientConnector.setSelectors(1);
        http2Client = new HTTP2Client(clientConnector);
        httpClient = new HttpClient(new HttpClientTransportOverHTTP2(http2Client));
        server.addBean(httpClient);
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testDefaultTransportProtocol() throws Exception
    {
        ServerConnector connector = new ServerConnector(server, 1, 1, new HTTP2CServerConnectionFactory());
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler());
        server.start();

        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));

        List<Destination> destinations = httpClient.getDestinations();
        assertThat(destinations.size(), is(1));
        Destination destination = destinations.get(0);
        assertThat(destination.getOrigin().getTransportProtocol(), sameInstance(TransportProtocol.TCP_IP));

        HttpClientTransportOverHTTP2 httpClientTransport = (HttpClientTransportOverHTTP2)httpClient.getTransport();
        int networkConnections = httpClientTransport.getHTTP2Client().getClientConnector().getSelectorManager().getTotalKeys();
        assertThat(networkConnections, is(1));
    }

    @Test
    public void testExplicitTransportProtocol() throws Exception
    {
        ServerConnector connector = new ServerConnector(server, 1, 1, new HTTP2CServerConnectionFactory());
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler());
        server.start();

        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .transportProtocol(TransportProtocol.TCP_IP)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    @Test
    public void testMemoryTransportProtocol() throws Exception
    {
        MemoryConnector connector = new MemoryConnector(server, new HTTP2CServerConnectionFactory());
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler());
        server.start();

        ContentResponse response = httpClient.newRequest("http://localhost/")
            .transportProtocol(new MemoryTransportProtocol(connector))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));

        HttpClientTransportOverHTTP2 httpClientTransport = (HttpClientTransportOverHTTP2)httpClient.getTransport();
        int networkConnections = httpClientTransport.getHTTP2Client().getClientConnector().getSelectorManager().getTotalKeys();
        assertThat(networkConnections, is(0));
    }

    @Test
    public void testUnixDomainTransportProtocol() throws Exception
    {
        UnixDomainServerConnector connector = new UnixDomainServerConnector(server, 1, 1, new HTTP2CServerConnectionFactory());
        connector.setUnixDomainPath(newUnixDomainPath());
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler());
        server.start();

        ContentResponse response = httpClient.newRequest("http://localhost/")
            .transportProtocol(new TransportProtocol.TCPUnix(connector.getUnixDomainPath()))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    @Test
    public void testQUICTransportProtocolWithH2C(WorkDir workDir) throws Exception
    {
        SslContextFactory.Server sslServer = new SslContextFactory.Server();
        sslServer.setKeyStorePath(MavenPaths.findTestResourceFile("keystore.p12").toString());
        sslServer.setKeyStorePassword("storepwd");

        Path pemServerDir = workDir.getEmptyPathDir().resolve("server");
        Files.createDirectories(pemServerDir);

        ServerQuicConfiguration quicConfiguration = new ServerQuicConfiguration(sslServer, pemServerDir);
        QuicServerConnector connector = new QuicServerConnector(server, quicConfiguration, new HTTP2CServerConnectionFactory());
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler());

        SslContextFactory.Client sslClient = new SslContextFactory.Client(true);
        httpClient.setSslContextFactory(sslClient);
        ClientQuicConfiguration clientQuicConfig = new ClientQuicConfiguration(sslClient, null);
        httpClient.addBean(clientQuicConfig);

        server.start();

        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .transportProtocol(new QuicTransportProtocol(clientQuicConfig))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    @Test
    public void testQUICTransportProtocolWithH2(WorkDir workDir) throws Exception
    {
        SslContextFactory.Server sslServer = new SslContextFactory.Server();
        sslServer.setKeyStorePath(MavenPaths.findTestResourceFile("keystore.p12").toString());
        sslServer.setKeyStorePassword("storepwd");

        Path pemServerDir = workDir.getEmptyPathDir().resolve("server");
        Files.createDirectories(pemServerDir);

        ServerQuicConfiguration quicConfiguration = new ServerQuicConfiguration(sslServer, pemServerDir);
        QuicServerConnector connector = new QuicServerConnector(server, quicConfiguration, new HTTP2ServerConnectionFactory());
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler());

        SslContextFactory.Client sslClient = new SslContextFactory.Client(true);
        httpClient.setSslContextFactory(sslClient);
        HttpClientTransportOverHTTP2 httpClientTransport = (HttpClientTransportOverHTTP2)httpClient.getTransport();
        // ALPN is negotiated by QUIC.
        httpClientTransport.setUseALPN(false);
        ClientQuicConfiguration clientQuicConfig = new ClientQuicConfiguration(sslClient, null);
        httpClient.addBean(clientQuicConfig);

        server.start();

        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .transportProtocol(new QuicTransportProtocol(clientQuicConfig))
            .scheme(HttpScheme.HTTPS.asString())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    @Test
    public void testLowLevelH2COverTCPIP() throws Exception
    {
        ServerConnector connector = new ServerConnector(server, 1, 1, new HTTP2CServerConnectionFactory());
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler());
        server.start();

        InetSocketAddress socketAddress = new InetSocketAddress("localhost", connector.getLocalPort());
        Session session = http2Client.connect(socketAddress, new Session.Listener() {}).get(5, TimeUnit.SECONDS);

        CountDownLatch responseLatch = new CountDownLatch(1);
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_2, HttpFields.EMPTY);
        session.newStream(new HeadersFrame(request, null, true), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                responseLatch.countDown();
            }
        });

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testLowLevelH2COverMemory() throws Exception
    {
        MemoryConnector connector = new MemoryConnector(server, new HTTP2CServerConnectionFactory());
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler());
        server.start();

        Session session = http2Client.connect(new MemoryTransportProtocol(connector), null, connector.getLocalSocketAddress(), new Session.Listener() {}).get(5, TimeUnit.SECONDS);

        CountDownLatch responseLatch = new CountDownLatch(1);
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_2, HttpFields.EMPTY);
        session.newStream(new HeadersFrame(request, null, true), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                responseLatch.countDown();
            }
        });

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testLowLevelH2COverUnixDomain() throws Exception
    {
        UnixDomainServerConnector connector = new UnixDomainServerConnector(server, new HTTP2CServerConnectionFactory());
        connector.setUnixDomainPath(newUnixDomainPath());
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler());
        server.start();

        Session session = http2Client.connect(new TransportProtocol.TCPUnix(connector.getUnixDomainPath()), null, connector.getLocalSocketAddress(), new Session.Listener() {}).get(5, TimeUnit.SECONDS);

        CountDownLatch responseLatch = new CountDownLatch(1);
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_2, HttpFields.EMPTY);
        session.newStream(new HeadersFrame(request, null, true), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                responseLatch.countDown();
            }
        });

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testLowLevelH2COverQUIC(WorkDir workDir) throws Exception
    {
        SslContextFactory.Server sslServer = new SslContextFactory.Server();
        sslServer.setKeyStorePath(MavenPaths.findTestResourceFile("keystore.p12").toString());
        sslServer.setKeyStorePassword("storepwd");

        Path pemServerDir = workDir.getEmptyPathDir().resolve("server");
        Files.createDirectories(pemServerDir);

        ServerQuicConfiguration quicConfiguration = new ServerQuicConfiguration(sslServer, pemServerDir);
        QuicServerConnector connector = new QuicServerConnector(server, quicConfiguration, new HTTP2CServerConnectionFactory());
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler());

        SslContextFactory.Client sslClient = new SslContextFactory.Client(true);
        http2Client.getClientConnector().setSslContextFactory(sslClient);
        ClientQuicConfiguration clientQuicConfig = new ClientQuicConfiguration(sslClient, null);
        clientQuicConfig.setProtocols(List.of("h2c"));
        http2Client.addBean(clientQuicConfig);

        server.start();

        SocketAddress socketAddress = new InetSocketAddress("localhost", connector.getLocalPort());
        Session session = http2Client.connect(new QuicTransportProtocol(clientQuicConfig), null, socketAddress, new Session.Listener() {}).get(5, TimeUnit.SECONDS);

        CountDownLatch responseLatch = new CountDownLatch(1);
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_2, HttpFields.EMPTY);
        session.newStream(new HeadersFrame(request, null, true), new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                responseLatch.countDown();
            }
        });

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    private static Path newUnixDomainPath()
    {
        String unixDomainDir = System.getProperty("jetty.unixdomain.dir", System.getProperty("java.io.tmpdir"));
        return Path.of(unixDomainDir, "jetty.sock");
    }
}
