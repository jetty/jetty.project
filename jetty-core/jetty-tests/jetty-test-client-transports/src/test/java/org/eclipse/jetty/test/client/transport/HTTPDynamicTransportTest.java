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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.transport.HttpClientConnectionFactory;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.HTTP2ClientConnectionFactory;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.http3.client.HTTP3Client;
import org.eclipse.jetty.http3.client.transport.ClientConnectionFactoryOverHTTP3;
import org.eclipse.jetty.http3.server.HTTP3ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.quic.client.ClientQuicConfiguration;
import org.eclipse.jetty.quic.client.QuicTransport;
import org.eclipse.jetty.quic.server.QuicServerConnectionFactory;
import org.eclipse.jetty.quic.server.QuicServerConnector;
import org.eclipse.jetty.quic.server.ServerQuicConfiguration;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.MemoryConnector;
import org.eclipse.jetty.server.MemoryTransport;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.unixdomain.server.UnixDomainServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.test.client.transport.AbstractTest.freePort;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HTTPDynamicTransportTest extends AbstractTransportTest
{
    private SslContextFactory.Server sslServer;
    private Path pemServerDir;
    private ClientConnector clientConnector;
    private HTTP2Client http2Client;
    private HTTP3Client http3Client;

    @BeforeEach
    public void prepare(WorkDir workDir) throws Exception
    {
        sslServer = new SslContextFactory.Server();
        sslServer.setKeyStorePath(MavenPaths.findTestResourceFile("keystore.p12").toString());
        sslServer.setKeyStorePassword("storepwd");
        pemServerDir = workDir.getEmptyPathDir().resolve("server");
        Files.createDirectories(pemServerDir);

        clientConnector = new ClientConnector();
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientConnector.setExecutor(clientThreads);
        clientConnector.setSelectors(1);

        http2Client = new HTTP2Client(clientConnector);

        SslContextFactory.Client sslClient = new SslContextFactory.Client(true);
        ClientQuicConfiguration quicConfiguration = new ClientQuicConfiguration(sslClient, null);
        http3Client = new HTTP3Client(quicConfiguration, clientConnector);
    }

    @Test
    public void testExplicitHTTPVersionWithSameHttpClientForAllHTTPVersions() throws Exception
    {
        int port = freePort();
        ConnectionFactory h1 = new HttpConnectionFactory();
        ConnectionFactory h2c = new HTTP2CServerConnectionFactory();
        ServerConnector tcp = new ServerConnector(server, 1, 1, h1, h2c);
        tcp.setPort(port);
        server.addConnector(tcp);

        ServerQuicConfiguration quicConfig = new ServerQuicConfiguration(sslServer, pemServerDir);
        ConnectionFactory h3 = new HTTP3ServerConnectionFactory(quicConfig);
        QuicServerConnector quic = new QuicServerConnector(server, quicConfig, h3);
        quic.setPort(port);
        server.addConnector(quic);

        server.setHandler(new EmptyServerHandler());

        HttpClientTransportDynamic httpClientTransport = new HttpClientTransportDynamic(
            clientConnector,
            HttpClientConnectionFactory.HTTP11,
            new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client),
            new ClientConnectionFactoryOverHTTP3.HTTP3(http3Client)
        );
        HttpClient httpClient = new HttpClient(httpClientTransport);
        server.addBean(httpClient);

        server.start();

        for (HttpVersion httpVersion : List.of(HttpVersion.HTTP_1_1, HttpVersion.HTTP_2, HttpVersion.HTTP_3))
        {
            ContentResponse response = httpClient.newRequest("localhost", port)
                .version(httpVersion)
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertThat(httpVersion.toString(), response.getStatus(), is(HttpStatus.OK_200));
        }
    }

    @Test
    public void testNonExplicitHTTPVersionH3H2H1() throws Exception
    {
        int port = freePort();
        ConnectionFactory h1 = new HttpConnectionFactory();
        ConnectionFactory h2c = new HTTP2CServerConnectionFactory();
        ServerConnector tcp = new ServerConnector(server, 1, 1, h1, h2c);
        tcp.setPort(port);
        server.addConnector(tcp);

        ServerQuicConfiguration quicConfig = new ServerQuicConfiguration(sslServer, pemServerDir);
        ConnectionFactory h3 = new HTTP3ServerConnectionFactory(quicConfig);
        QuicServerConnector quic = new QuicServerConnector(server, quicConfig, h3);
        quic.setPort(port);
        server.addConnector(quic);

        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, true, request.getConnectionMetaData().getProtocol(), callback);
                return true;
            }
        });

        HttpClientTransportDynamic httpClientTransport = new HttpClientTransportDynamic(
            clientConnector,
            new ClientConnectionFactoryOverHTTP3.HTTP3(http3Client),
            new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client),
            HttpClientConnectionFactory.HTTP11
        );
        HttpClient httpClient = new HttpClient(httpClientTransport);
        server.addBean(httpClient);

        server.start();

        // No explicit version, HttpClientTransport preference wins.
        ContentResponse response = httpClient.newRequest("localhost", port)
            .scheme(HttpScheme.HTTPS.asString())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), containsString("/3"));

        // Non-secure scheme, must not be HTTP/3.
        response = httpClient.newRequest("localhost", port)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), containsString("/2"));
    }

    @Test
    public void testNonExplicitHTTPVersionH2H3H1() throws Exception
    {
        int port = freePort();
        ConnectionFactory h1 = new HttpConnectionFactory();
        ConnectionFactory h2c = new HTTP2CServerConnectionFactory();
        ServerConnector tcp = new ServerConnector(server, 1, 1, h1, h2c);
        tcp.setPort(port);
        server.addConnector(tcp);

        int securePort = freePort();
        ConnectionFactory h2 = new HTTP2ServerConnectionFactory();
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h1.getProtocol());
        ConnectionFactory ssl = new SslConnectionFactory(sslServer, alpn.getProtocol());
        ServerConnector tcpSecure = new ServerConnector(server, 1, 1, ssl, alpn, h2, h1);
        tcpSecure.setPort(securePort);
        server.addConnector(tcpSecure);

        ServerQuicConfiguration quicConfig = new ServerQuicConfiguration(sslServer, pemServerDir);
        ConnectionFactory h3 = new HTTP3ServerConnectionFactory(quicConfig);
        QuicServerConnector quic = new QuicServerConnector(server, quicConfig, h3);
        quic.setPort(securePort);
        server.addConnector(quic);

        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, true, request.getConnectionMetaData().getProtocol(), callback);
                return true;
            }
        });

        HttpClientTransportDynamic httpClientTransport = new HttpClientTransportDynamic(
            clientConnector,
            new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client),
            new ClientConnectionFactoryOverHTTP3.HTTP3(http3Client),
            HttpClientConnectionFactory.HTTP11
        );
        HttpClient httpClient = new HttpClient(httpClientTransport);
        server.addBean(httpClient);

        server.start();

        // No explicit version, non-secure, HttpClientTransport preference wins.
        ContentResponse response = httpClient.newRequest("localhost", port)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), containsString("/2"));

        // Secure scheme, but must not be HTTP/3.
        response = httpClient.newRequest("localhost", securePort)
            .scheme(HttpScheme.HTTPS.asString())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), containsString("/2"));
    }

    @Test
    public void testClientH2H3H1ServerALPNH1() throws Exception
    {
        int securePort = freePort();

        ConnectionFactory h1 = new HttpConnectionFactory();
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h1.getProtocol());
        ConnectionFactory ssl = new SslConnectionFactory(sslServer, alpn.getProtocol());
        ServerConnector tcpSecure = new ServerConnector(server, 1, 1, ssl, alpn, h1);
        tcpSecure.setPort(securePort);
        server.addConnector(tcpSecure);

        ServerQuicConfiguration quicConfig = new ServerQuicConfiguration(sslServer, pemServerDir);
        ConnectionFactory h3 = new HTTP3ServerConnectionFactory(quicConfig);
        QuicServerConnector quic = new QuicServerConnector(server, quicConfig, h3);
        quic.setPort(securePort);
        server.addConnector(quic);

        server.setHandler(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                Content.Sink.write(response, true, request.getConnectionMetaData().getProtocol(), callback);
                return true;
            }
        });

        HttpClientTransportDynamic httpClientTransport = new HttpClientTransportDynamic(
            clientConnector,
            new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client),
            new ClientConnectionFactoryOverHTTP3.HTTP3(http3Client),
            HttpClientConnectionFactory.HTTP11
        );
        HttpClient httpClient = new HttpClient(httpClientTransport);
        server.addBean(httpClient);

        server.start();

        // Secure scheme, must negotiate HTTP/1.
        ContentResponse response = httpClient.newRequest("localhost", securePort)
            .scheme(HttpScheme.HTTPS.asString())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContentAsString(), containsString("/1"));
    }

    @Test
    public void testClientSendH3ServerDoesNotSupportH3() throws Exception
    {
        ConnectionFactory h2 = new HTTP2ServerConnectionFactory();
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h2.getProtocol());
        ConnectionFactory ssl = new SslConnectionFactory(sslServer, alpn.getProtocol());
        ServerConnector tcpSecure = new ServerConnector(server, 1, 1, ssl, alpn, h2);
        server.addConnector(tcpSecure);

        server.setHandler(new EmptyServerHandler());

        HttpClientTransportDynamic httpClientTransport = new HttpClientTransportDynamic(
            clientConnector,
            new ClientConnectionFactoryOverHTTP3.HTTP3(http3Client),
            new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client)
        );
        HttpClient httpClient = new HttpClient(httpClientTransport);
        server.addBean(httpClient);

        server.start();

        // The client will attempt a request with H3 due to client preference.
        // The attempt to connect via QUIC/UDP will time out (there is no immediate
        // failure like would happen with TCP not listening on the connector port).
        assertThrows(TimeoutException.class, () -> httpClient.newRequest("localhost", tcpSecure.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .timeout(1, TimeUnit.SECONDS)
            .send()
        );

        // Make sure the client can speak H2.
        ContentResponse response = httpClient.newRequest("localhost", tcpSecure.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .version(HttpVersion.HTTP_2)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    @Test
    public void testHighLevelH1OverUNIX() throws Exception
    {
        ConnectionFactory h1 = new HttpConnectionFactory();
        ServerConnector tcp = new ServerConnector(server, 1, 1, h1);
        server.addConnector(tcp);

        Path unixDomainPath = newUnixDomainPath();
        UnixDomainServerConnector unix = new UnixDomainServerConnector(server, 1, 1, h1);
        unix.setUnixDomainPath(unixDomainPath);
        server.addConnector(unix);

        server.setHandler(new EmptyServerHandler());

        HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(new ClientConnector(), HttpClientConnectionFactory.HTTP11));
        server.addBean(httpClient);

        server.start();

        ContentResponse response = httpClient.newRequest("localhost", tcp.getLocalPort())
            .transport(new Transport.TCPUnix(unixDomainPath))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(tcp.getConnectedEndPoints().size(), is(0));
        assertThat(unix.getConnectedEndPoints().size(), is(1));
    }

    @Test
    public void testLowLevelH2OverUNIX() throws Exception
    {
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setServerAuthority(new HostPort("localhost"));
        ConnectionFactory h2c = new HTTP2CServerConnectionFactory(httpConfig);
        ServerConnector tcp = new ServerConnector(server, 1, 1, h2c);
        server.addConnector(tcp);

        Path unixDomainPath = newUnixDomainPath();
        UnixDomainServerConnector unix = new UnixDomainServerConnector(server, 1, 1, h2c);
        unix.setUnixDomainPath(unixDomainPath);
        server.addConnector(unix);

        server.setHandler(new EmptyServerHandler());

        server.addBean(http2Client);

        server.start();

        Transport.TCPUnix transport = new Transport.TCPUnix(unixDomainPath);
        Promise.Completable<Session> promise = new Promise.Completable<>();
        http2Client.connect(transport, null, new HTTP2ClientConnectionFactory(), new Session.Listener() {}, promise, null);
        Session session = promise.get(5, TimeUnit.SECONDS);

        CountDownLatch responseLatch = new CountDownLatch(1);
        MetaData.Request request = new MetaData.Request("GET", HttpURI.from("http://localhost/path"), HttpVersion.HTTP_2, HttpFields.EMPTY);
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
    public void testHighLevelH1OverMemory() throws Exception
    {
        ConnectionFactory h1 = new HttpConnectionFactory();
        MemoryConnector local = new MemoryConnector(server, h1);
        server.addConnector(local);

        server.setHandler(new EmptyServerHandler());

        HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic());
        server.addBean(httpClient);

        server.start();

        ContentResponse response = httpClient.newRequest("http://localhost/")
            .transport(new MemoryTransport(local))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    @Test
    public void testHighLevelH2OverQUIC(WorkDir workDir) throws Exception
    {
        SslContextFactory.Server sslServer = new SslContextFactory.Server();
        sslServer.setKeyStorePath(MavenPaths.findTestResourceFile("keystore.p12").toString());
        sslServer.setKeyStorePassword("storepwd");

        ConnectionFactory h2c = new HTTP2CServerConnectionFactory(new HttpConfiguration());
        ServerQuicConfiguration serverQuicConfiguration = new ServerQuicConfiguration(sslServer, null);
        QuicServerConnector connector = new QuicServerConnector(server, serverQuicConfiguration, h2c);
        connector.getQuicConfiguration().setPemWorkDirectory(workDir.getEmptyPathDir());
        server.addConnector(connector);

        server.setHandler(new EmptyServerHandler());

        HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(clientConnector, new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client)));
        server.addBean(httpClient);

        SslContextFactory.Client sslClient = new SslContextFactory.Client(true);
        httpClient.addBean(sslClient);

        server.start();

        ClientQuicConfiguration clientQuicConfiguration = new ClientQuicConfiguration(sslClient, null);
        QuicTransport transport = new QuicTransport(clientQuicConfiguration);

        ContentResponse response = httpClient.newRequest("localhost", connector.getLocalPort())
            .transport(transport)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    @Test
    public void testHighLevelH3OverMemory(WorkDir workDir) throws Exception
    {
        SslContextFactory.Server sslServer = new SslContextFactory.Server();
        sslServer.setKeyStorePath(MavenPaths.findTestResourceFile("keystore.p12").toString());
        sslServer.setKeyStorePassword("storepwd");

        HttpConnectionFactory h1 = new HttpConnectionFactory();
        ServerQuicConfiguration quicConfiguration = new ServerQuicConfiguration(sslServer, workDir.getEmptyPathDir());
        QuicServerConnectionFactory quic = new QuicServerConnectionFactory(quicConfiguration);
        HTTP3ServerConnectionFactory h3 = new HTTP3ServerConnectionFactory(quicConfiguration);

        MemoryConnector connector = new MemoryConnector(server, quic, h1, h3);
        server.addConnector(connector);

        server.setHandler(new EmptyServerHandler());

        HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(clientConnector, new ClientConnectionFactoryOverHTTP3.HTTP3(http3Client)));
        server.addBean(httpClient);

        server.start();

        Transport transport = new QuicTransport(new MemoryTransport(connector), http3Client.getQuicConfiguration());

        ContentResponse response = httpClient.newRequest("https://localhost/")
            .transport(transport)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }
}
