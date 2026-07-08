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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.api.Stream;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.HTTP3ClientQuicConfiguration;
import org.eclipse.jetty.http3.client.transport.HttpClientTransportOverHTTP3;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.http3.server.HTTP3ServerQuicConfiguration;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.quic.client.QuicClient;
import org.eclipse.jetty.quic.client.QuicClientQuicConfiguration;
import org.eclipse.jetty.quic.client.QuicTransport;
import org.eclipse.jetty.quic.server.QuicServerConnectionFactory;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.quic.server.QuicServerQuicConfiguration;
import org.eclipse.jetty.server.MemoryConnector;
import org.eclipse.jetty.server.MemoryTransport;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.util.Blocker;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class HTTP3TransportTest extends AbstractTransportTest
{
    private SslContextFactory.Server sslServer;
    private HttpClient httpClient;
    private HTTP3Client http3Client;

    @BeforeEach
    public void prepare()
    {
        sslServer = new SslContextFactory.Server();
        sslServer.setKeyStorePath(MavenPaths.findTestResourceFile("keystore.p12").toString());
        sslServer.setKeyStorePassword("storepwd");

        ClientConnector clientConnector = new ClientConnector();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientConnector.setExecutor(clientThreads);
        clientConnector.setSelectors(1);
        clientConnector.setSslContextFactory(new SslContextFactory.Client(true));
        QuicClientQuicConfiguration clientQuicConfig = HTTP3ClientQuicConfiguration.configure(new QuicClientQuicConfiguration());
        http3Client = new HTTP3Client(clientQuicConfig, clientConnector);
        httpClient = new HttpClient(new HttpClientTransportOverHTTP3(http3Client, new QuicTransport(new QuicClient(clientQuicConfig))));
        server.addBean(httpClient);
    }

    @Test
    public void testDefaultTransport() throws Exception
    {
        QuicServerQuicConfiguration serverQuicConfig = HTTP3ServerQuicConfiguration.configure(new QuicServerQuicConfiguration());
        QuicServerConnector connector = new QuicServerConnector(server, sslServer, serverQuicConfig, new HTTP3ServerConnectionFactory());
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
        Transport transport = destination.getOrigin().getTransport();
        if (transport instanceof Transport.Wrapper wrapper)
            transport = wrapper.unwrap();
        assertThat(transport, sameInstance(Transport.UDP_IP));

        HttpClientTransportOverHTTP3 httpClientTransport = (HttpClientTransportOverHTTP3)httpClient.getHttpClientTransport();
        int networkConnections = httpClientTransport.getHTTP3Client().getClientConnector().getSelectorManager().getTotalKeys();
        assertThat(networkConnections, is(1));
    }

    @Test
    public void testExplicitTransport() throws Exception
    {
        QuicServerQuicConfiguration serverQuicConfig = HTTP3ServerQuicConfiguration.configure(new QuicServerQuicConfiguration());
        QuicServerConnector connector = new QuicServerConnector(server, sslServer, serverQuicConfig, new HTTP3ServerConnectionFactory());
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler());
        server.start();

        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .transport(new QuicTransport(new QuicClient((QuicClientQuicConfiguration)http3Client.getClientQuicConfiguration())))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    @Test
    public void testMemoryTransport() throws Exception
    {
        QuicServerQuicConfiguration serverQuicConfig = HTTP3ServerQuicConfiguration.configure(new QuicServerQuicConfiguration());
        QuicServerConnectionFactory quic = new QuicServerConnectionFactory(sslServer, serverQuicConfig);
        HTTP3ServerConnectionFactory h3 = new HTTP3ServerConnectionFactory();
        MemoryConnector connector = new MemoryConnector(server, quic, h3);
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler());
        server.start();

        ContentResponse response = httpClient.newRequest("http://localhost/")
            .transport(new QuicTransport(new MemoryTransport(connector), new QuicClient((QuicClientQuicConfiguration)http3Client.getClientQuicConfiguration())))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));

        HttpClientTransportOverHTTP3 httpClientTransport = (HttpClientTransportOverHTTP3)httpClient.getHttpClientTransport();
        int networkConnections = httpClientTransport.getHTTP3Client().getClientConnector().getSelectorManager().getTotalKeys();
        assertThat(networkConnections, is(0));
    }

    @Test
    public void testUnixDomainTransport()
    {
        noUnixDomainForDatagramChannel();
    }

    @Test
    public void testLowLevelH3OverUDPIP() throws Exception
    {
        QuicServerQuicConfiguration serverQuicConfig = HTTP3ServerQuicConfiguration.configure(new QuicServerQuicConfiguration());
        QuicServerConnector connector = new QuicServerConnector(server, sslServer, serverQuicConfig, new HTTP3ServerConnectionFactory());
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler());
        server.start();

        InetSocketAddress socketAddress = new InetSocketAddress("localhost", connector.getLocalPort());
        Session.Client session = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> http3Client.connect(new QuicTransport(new QuicClient((QuicClientQuicConfiguration)http3Client.getClientQuicConfiguration())), socketAddress, new Session.Client.Listener() {}, p));

        CountDownLatch responseLatch = new CountDownLatch(1);
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_3, HttpFields.EMPTY);
        session.newRequest(new HeadersFrame(request, true), new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                responseLatch.countDown();
            }
        }, Promise.Invocable.noop());

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testLowLevelH3OverMemory() throws Exception
    {
        QuicServerQuicConfiguration serverQuicConfig = HTTP3ServerQuicConfiguration.configure(new QuicServerQuicConfiguration());
        QuicServerConnectionFactory quic = new QuicServerConnectionFactory(sslServer, serverQuicConfig);
        HTTP3ServerConnectionFactory h3 = new HTTP3ServerConnectionFactory();
        MemoryConnector connector = new MemoryConnector(server, quic, h3);
        server.addConnector(connector);
        server.setHandler(new EmptyServerHandler());
        server.start();

        Transport transport = new QuicTransport(new MemoryTransport(connector), new QuicClient((QuicClientQuicConfiguration)http3Client.getClientQuicConfiguration()));
        Session.Client session = Blocker.blockWithPromise(5, TimeUnit.SECONDS, p -> http3Client.connect(transport, connector.getLocalSocketAddress(), new Session.Client.Listener() {}, p));

        CountDownLatch responseLatch = new CountDownLatch(1);
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/"), HttpVersion.HTTP_3, HttpFields.EMPTY);
        session.newRequest(new HeadersFrame(request, true), new Stream.Client.Listener()
        {
            @Override
            public void onResponse(Stream.Client stream, HeadersFrame frame)
            {
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                assertThat(response.getStatus(), is(HttpStatus.OK_200));
                responseLatch.countDown();
            }
        }, Promise.Invocable.noop());

        assertTrue(responseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testLowLevelH3OverUnixDomain()
    {
        noUnixDomainForDatagramChannel();
    }

    private static void noUnixDomainForDatagramChannel()
    {
        assumeTrue(false, "DatagramChannel over Unix-Domain is not supported yet by Java");
    }
}
