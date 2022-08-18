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

package org.eclipse.jetty.test.client.transport;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.AbstractConnectionPool;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.client.util.BufferingResponseListener;
import org.eclipse.jetty.client.util.BytesRequestContent;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.transport.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.ProxyConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.client.ProxyProtocolClientConnectionFactory.V1;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientTransportDynamicTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    private void startServer(Function<Server, ServerConnector> connectorFn, Handler handler) throws Exception
    {
        prepareServer(connectorFn, handler);
        server.start();
    }

    private void prepareServer(Function<Server, ServerConnector> connectorFn, Handler handler)
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        serverThreads.setDetailedDump(true);
        server = new Server(serverThreads);
        connector = connectorFn.apply(server);
        server.setHandler(handler);
    }

    private void startClient(HttpClientConnectionFactory.Info... infos) throws Exception
    {
        ClientConnector clientConnector = new ClientConnector();
        startClient(clientConnector, infos);
    }

    private void startClient(ClientConnector clientConnector, HttpClientConnectionFactory.Info... infos) throws Exception
    {
        clientConnector.setSelectors(1);
        clientConnector.setSslContextFactory(newClientSslContextFactory());
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        clientConnector.setExecutor(clientThreads);
        client = new HttpClient(new HttpClientTransportDynamic(clientConnector, infos));
        client.start();
    }

    private ServerConnector h1(Server server)
    {
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        HttpConnectionFactory h1 = new HttpConnectionFactory(httpConfiguration);
        ServerConnector connector = new ServerConnector(server, 1, 1, h1);
        server.addConnector(connector);
        return connector;
    }

    private ServerConnector h1H2C(Server server)
    {
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        HttpConnectionFactory h1 = new HttpConnectionFactory(httpConfiguration);
        HTTP2CServerConnectionFactory h2c = new HTTP2CServerConnectionFactory(httpConfiguration);
        ServerConnector connector = new ServerConnector(server, 1, 1, h1, h2c);
        server.addConnector(connector);
        return connector;
    }

    private ServerConnector sslAlpnH1(Server server)
    {
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        HttpConnectionFactory h1 = new HttpConnectionFactory(httpConfiguration);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h1.getProtocol());
        SslContextFactory.Server sslContextFactory = newServerSslContextFactory();
        ServerConnector connector = new ServerConnector(server, 1, 1, AbstractConnectionFactory.getFactories(sslContextFactory, alpn, h1));
        server.addConnector(connector);
        return connector;
    }

    private ServerConnector sslH1H2C(Server server)
    {
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        HttpConnectionFactory h1 = new HttpConnectionFactory(httpConfiguration);
        HTTP2CServerConnectionFactory h2c = new HTTP2CServerConnectionFactory(httpConfiguration);
        SslContextFactory.Server sslContextFactory = newServerSslContextFactory();
        // No ALPN.
        ServerConnector connector = new ServerConnector(server, 1, 1, AbstractConnectionFactory.getFactories(sslContextFactory, h1, h2c));
        server.addConnector(connector);
        return connector;
    }

    private ServerConnector sslAlpnH1H2(Server server)
    {
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        HttpConnectionFactory h1 = new HttpConnectionFactory(httpConfiguration);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h1.getProtocol());
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpConfiguration);
        SslContextFactory.Server sslContextFactory = newServerSslContextFactory();
        // Make explicitly h1 the default protocol (normally it would be h2).
        ServerConnector connector = new ServerConnector(server, 1, 1, AbstractConnectionFactory.getFactories(sslContextFactory, alpn, h1, h2));
        server.addConnector(connector);
        return connector;
    }

    private ServerConnector sslAlpnH2H1(Server server)
    {
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        HttpConnectionFactory h1 = new HttpConnectionFactory(httpConfiguration);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h1.getProtocol());
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpConfiguration);
        SslContextFactory.Server sslContextFactory = newServerSslContextFactory();
        ServerConnector connector = new ServerConnector(server, 1, 1, AbstractConnectionFactory.getFactories(sslContextFactory, alpn, h2, h1));
        server.addConnector(connector);
        return connector;
    }

    private ServerConnector proxyH1H2C(Server server)
    {
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        HttpConnectionFactory h1 = new HttpConnectionFactory(httpConfiguration);
        ProxyConnectionFactory proxy = new ProxyConnectionFactory(h1.getProtocol());
        ServerConnector connector = new ServerConnector(server, 1, 1, proxy, h1);
        server.addConnector(connector);
        return connector;
    }

    @AfterEach
    public void stop() throws Exception
    {
        if (server != null)
            server.stop();
        if (client != null)
            client.stop();
    }

    private SslContextFactory.Client newClientSslContextFactory()
    {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
        configureSslContextFactory(sslContextFactory);
        sslContextFactory.setEndpointIdentificationAlgorithm(null);
        return sslContextFactory;
    }

    private SslContextFactory.Server newServerSslContextFactory()
    {
        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
        configureSslContextFactory(sslContextFactory);
        return sslContextFactory;
    }

    private void configureSslContextFactory(SslContextFactory sslContextFactory)
    {
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.p12");
        sslContextFactory.setKeyStorePassword("storepwd");
        // The mandatory HTTP/2 cipher.
        sslContextFactory.setIncludeCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
    }

    @Test
    public void testClearTextHTTP1() throws Exception
    {
        startServer(this::h1H2C, new EmptyServerHandler());
        startClient(HttpClientConnectionFactory.HTTP11);

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testClearTextHTTP2() throws Exception
    {
        startServer(this::h1H2C, new EmptyServerHandler());
        ClientConnector clientConnector = new ClientConnector();
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info h2c = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);
        startClient(clientConnector, h2c);
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
//                .version(HttpVersion.HTTP_2)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testClearTextProtocolSelection() throws Exception
    {
        startServer(this::h1H2C, new EmptyServerHandler());
        testProtocolSelection(HttpScheme.HTTP);
    }

    @Test
    public void testEncryptedProtocolSelectionWithoutNegotiation() throws Exception
    {
        startServer(this::sslH1H2C, new EmptyServerHandler());
        testProtocolSelection(HttpScheme.HTTPS);
    }

    private void testProtocolSelection(HttpScheme scheme) throws Exception
    {
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(newClientSslContextFactory());
        HttpClientConnectionFactory.Info h1 = HttpClientConnectionFactory.HTTP11;
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);
        HttpClientTransportDynamic transport = new HttpClientTransportDynamic(clientConnector, h1, http2)
        {
            @Override
            public Origin newOrigin(HttpRequest request)
            {
                // Use prior-knowledge, i.e. negotiate==false.
                boolean secure = HttpClient.isSchemeSecure(request.getScheme());
                List<String> protocols = HttpVersion.HTTP_2 == request.getVersion() ? http2.getProtocols(secure) : h1.getProtocols(secure);
                return new Origin(request.getScheme(), request.getHost(), request.getPort(), request.getTag(), new Origin.Protocol(protocols, false));
            }
        };
        client = new HttpClient(transport);
        client.start();

        // Make a HTTP/1.1 request.
        ContentResponse h1cResponse = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scheme.asString())
            .version(HttpVersion.HTTP_1_1)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, h1cResponse.getStatus());

        // Make a HTTP/2 request.
        ContentResponse h2cResponse = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scheme.asString())
            .version(HttpVersion.HTTP_2)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, h2cResponse.getStatus());

        // We must have 2 different destinations with the same origin.
        List<Destination> destinations = client.getDestinations();
        assertEquals(2, destinations.size());
        assertEquals(1, destinations.stream()
            .map(HttpDestination.class::cast)
            .map(HttpDestination::getOrigin)
            .map(Origin::asString)
            .distinct()
            .count());
    }

    @Test
    public void testEncryptedProtocolSelectionWithNegotiation() throws Exception
    {
        startServer(this::sslAlpnH1H2, new EmptyServerHandler());
        ClientConnector clientConnector = new ClientConnector();
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);
        startClient(clientConnector, HttpClientConnectionFactory.HTTP11, http2);

        // Make a request, should be HTTP/1.1 because of the order of protocols on server.
        ContentResponse h1cResponse = client.newRequest("localhost", connector.getLocalPort())
            .scheme("https")
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, h1cResponse.getStatus());

        // Now clearly specify HTTP/2 in the client.
        ContentResponse h2cResponse = client.newRequest("localhost", connector.getLocalPort())
            .scheme("https")
            .version(HttpVersion.HTTP_2)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, h2cResponse.getStatus());

        // We must have 2 different destinations with the same origin.
        List<Destination> destinations = client.getDestinations();
        assertEquals(2, destinations.size());
        assertEquals(1, destinations.stream()
            .map(HttpDestination.class::cast)
            .map(HttpDestination::getOrigin)
            .map(Origin::asString)
            .distinct()
            .count());
    }

    @Test
    public void testServerOnlySpeaksEncryptedHTTP11ClientFallsBackToHTTP11() throws Exception
    {
        startServer(this::sslAlpnH1, new EmptyServerHandler());
        ClientConnector clientConnector = new ClientConnector();
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);
        startClient(clientConnector, http2, HttpClientConnectionFactory.HTTP11);

        // The client prefers h2 over h1, and use of TLS and ALPN will allow the fallback to h1.
        ContentResponse h1cResponse = client.newRequest("localhost", connector.getLocalPort())
            .scheme("https")
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, h1cResponse.getStatus());
    }

    @Test
    public void testServerOnlySpeaksClearTextHTTP11ClientFailsHTTP2() throws Exception
    {
        startServer(this::h1, new EmptyServerHandler());
        ClientConnector clientConnector = new ClientConnector();
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);
        startClient(clientConnector, HttpClientConnectionFactory.HTTP11, http2);

        // The client forces HTTP/2, but the server cannot speak it, so the request fails.
        // There is no fallback to HTTP/1 because the protocol version is set explicitly.
        assertThrows(ExecutionException.class, () -> client.newRequest("localhost", connector.getLocalPort())
            .version(HttpVersion.HTTP_2)
            .timeout(5, TimeUnit.SECONDS)
            .send());
    }

    @Test
    public void testDestinationClientConnectionFactoryWrapped() throws Exception
    {
        // A more complicated test simulating a reverse proxy.
        //
        // If a reverse proxy is totally stateless, it can proxy multiple clients
        // using the same connection pool (and hence just one destination).
        //
        // However, if we want to use the PROXY protocol, the proxy should have one
        // destination per client IP:port, because we need to send the PROXY bytes
        // with the client IP:port when opening a connection to the server.
        // Note that if the client speaks HTTP/2 to the proxy, but the proxy speaks
        // HTTP/1.1 to the server, there may be the need for the proxy to have
        // multiple HTTP/1.1 connections for the same client IP:port.

        // client :1234 <-> :8888 proxy :5678 <-> server :8080
        // client :2345 <-> :8888 proxy :6789 <-> server :8080

        startServer(this::proxyH1H2C, new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN.asString());
                Content.Sink.write(response, true, String.valueOf(Request.getRemotePort(request)), callback);
            }
        });
        startClient(HttpClientConnectionFactory.HTTP11);

        // Simulate a proxy request to the server.
        HttpRequest proxyRequest1 = (HttpRequest)client.newRequest("localhost", connector.getLocalPort());
        // Map the proxy request to client IP:port.
        int clientPort1 = ThreadLocalRandom.current().nextInt(1024, 65536);
        proxyRequest1.tag(new V1.Tag("localhost", clientPort1));
        ContentResponse proxyResponse1 = proxyRequest1
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(String.valueOf(clientPort1), proxyResponse1.getContentAsString());

        // Simulate another request to the server, from a different client port.
        HttpRequest proxyRequest2 = (HttpRequest)client.newRequest("localhost", connector.getLocalPort());
        int clientPort2 = ThreadLocalRandom.current().nextInt(1024, 65536);
        proxyRequest2.tag(new V1.Tag("localhost", clientPort2));
        ContentResponse proxyResponse2 = proxyRequest2
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(String.valueOf(clientPort2), proxyResponse2.getContentAsString());

        // We must have 2 different destinations with the same origin.
        List<Destination> destinations = client.getDestinations();
        assertEquals(2, destinations.size());
        assertEquals(1, destinations.stream()
            .map(HttpDestination.class::cast)
            .map(HttpDestination::getOrigin)
            .map(Origin::asString)
            .distinct()
            .count());
    }

    @Test
    public void testClearTextAndEncryptedHTTP2() throws Exception
    {
        prepareServer(this::sslAlpnH2H1, new EmptyServerHandler());
        ServerConnector clearConnector = h1H2C(server);
        server.start();
        ClientConnector clientConnector = new ClientConnector();
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);
        startClient(clientConnector, HttpClientConnectionFactory.HTTP11, http2);

        // Make a clear-text request using HTTP/1.1.
        ContentResponse h1cResponse = client.newRequest("localhost", clearConnector.getLocalPort())
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, h1cResponse.getStatus());

        // Make a clear-text request using HTTP/2.
        ContentResponse h2cResponse = client.newRequest("localhost", clearConnector.getLocalPort())
            .version(HttpVersion.HTTP_2)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, h2cResponse.getStatus());

        // Make an encrypted request without specifying the protocol.
        // Because the server prefers h2, this request will be HTTP/2, but will
        // generate a different destination than an explicit HTTP/2 request (like below).
        ContentResponse h1Response = client.newRequest("localhost", connector.getLocalPort())
            .scheme("https")
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, h1Response.getStatus());

        // Make an encrypted request using explicitly HTTP/2.
        ContentResponse h2Response = client.newRequest("localhost", connector.getLocalPort())
            .scheme("https")
            .version(HttpVersion.HTTP_2)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, h2Response.getStatus());

        // There should be 4 destinations with 2 origins.
        List<Destination> destinations = client.getDestinations();
        assertEquals(4, destinations.size());
        assertEquals(2, destinations.stream()
            .map(HttpDestination.class::cast)
            .map(HttpDestination::getOrigin)
            .map(Origin::asString)
            .distinct()
            .count());
    }

    @Test
    public void testHTTP11UpgradeToH2C() throws Exception
    {
        String content = "upgrade";
        startServer(this::h1H2C, new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_UTF_8.asString());
                Content.Sink.write(response, true, content, callback);
            }
        });
        ClientConnector clientConnector = new ClientConnector();
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);
        startClient(clientConnector, HttpClientConnectionFactory.HTTP11, http2);

        // Make an upgrade request from HTTP/1.1 to H2C.
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .headers(headers -> headers
                .put(HttpHeader.UPGRADE, "h2c")
                .put(HttpHeader.HTTP2_SETTINGS, "")
                .put(HttpHeader.CONNECTION, "Upgrade, HTTP2-Settings"))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(content, response.getContentAsString());
        // We must have 2 different destinations with the same origin.
        List<Destination> destinations = client.getDestinations();
        assertEquals(2, destinations.size());
        HttpDestination h1Destination = (HttpDestination)destinations.get(0);
        HttpDestination h2Destination = (HttpDestination)destinations.get(1);
        if (h2Destination.getOrigin().getProtocol().getProtocols().contains("http/1.1"))
        {
            HttpDestination swap = h1Destination;
            h1Destination = h2Destination;
            h2Destination = swap;
        }
        List<String> protocols1 = h1Destination.getOrigin().getProtocol().getProtocols();
        assertEquals(1, protocols1.size());
        assertTrue(protocols1.contains("http/1.1"));
        AbstractConnectionPool h1ConnectionPool = (AbstractConnectionPool)h1Destination.getConnectionPool();
        assertTrue(h1ConnectionPool.isEmpty());
        List<String> protocols2 = h2Destination.getOrigin().getProtocol().getProtocols();
        assertEquals(1, protocols2.size());
        assertTrue(protocols2.contains("h2c"));
        AbstractConnectionPool h2ConnectionPool = (AbstractConnectionPool)h2Destination.getConnectionPool();
        assertEquals(1, h2ConnectionPool.getConnectionCount());

        // Make a normal HTTP/2 request.
        response = client.newRequest("localhost", connector.getLocalPort())
            .version(HttpVersion.HTTP_2)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(content, response.getContentAsString());
        // We still have 2 destinations.
        assertEquals(2, client.getDestinations().size());
        // We still have 1 HTTP/2 connection.
        assertEquals(1, h2ConnectionPool.getConnectionCount());

        // Make a normal HTTP/1.1 request.
        response = client.newRequest("localhost", connector.getLocalPort())
            .version(HttpVersion.HTTP_1_1)
            .timeout(5, TimeUnit.SECONDS)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(content, response.getContentAsString());
        // We still have 2 destinations.
        assertEquals(2, client.getDestinations().size());
    }

    @Test
    public void testHTTP11UpgradeToH2CWithForwardProxy() throws Exception
    {
        String content = "upgrade";
        startServer(this::h1H2C, new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_UTF_8.asString());
                Content.Sink.write(response, true, content, callback);
            }
        });
        ClientConnector clientConnector = new ClientConnector();
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);
        startClient(clientConnector, HttpClientConnectionFactory.HTTP11, http2);

        int proxyPort = connector.getLocalPort();
        // The proxy speaks both http/1.1 and h2c.
        Origin.Protocol proxyProtocol = new Origin.Protocol(List.of("http/1.1", "h2c"), false);
        client.getProxyConfiguration().getProxies().add(new HttpProxy(new Origin.Address("localhost", proxyPort), false, proxyProtocol));

        // Make an upgrade request from HTTP/1.1 to H2C.
        int serverPort = proxyPort + 1; // Any port will do.
        ContentResponse response = client.newRequest("localhost", serverPort)
            .headers(headers -> headers
                .put(HttpHeader.UPGRADE, "h2c")
                .put(HttpHeader.HTTP2_SETTINGS, "")
                .put(HttpHeader.CONNECTION, "Upgrade, HTTP2-Settings"))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(content, response.getContentAsString());
        // Verify that we upgraded.
        assertEquals(2, client.getDestinations().size());
    }

    @Test
    public void testHTTP11UpgradeToH2COverTLS() throws Exception
    {
        String content = "upgrade";
        startServer(this::h1H2C, new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                response.getHeaders().put(HttpHeader.CONTENT_TYPE, MimeTypes.Type.TEXT_PLAIN_UTF_8.asString());
                Content.Sink.write(response, true, content, callback);
            }
        });
        ClientConnector clientConnector = new ClientConnector();
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);
        startClient(clientConnector, HttpClientConnectionFactory.HTTP11, http2);

        // Make an upgrade request from HTTP/1.1 to H2C.
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .headers(headers -> headers
                .put(HttpHeader.UPGRADE, "h2c")
                .put(HttpHeader.HTTP2_SETTINGS, "")
                .put(HttpHeader.CONNECTION, "Upgrade, HTTP2-Settings"))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(content, response.getContentAsString());
        // Verify that we upgraded.
        assertEquals(2, client.getDestinations().size());
    }

    @Test
    public void testHTTP11UpgradeToH2CWithRequestContentDoesNotUpgrade() throws Exception
    {
        startServer(this::h1H2C, new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                Content.copy(request, response, callback);
            }
        });
        ClientConnector clientConnector = new ClientConnector();
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);
        startClient(clientConnector, HttpClientConnectionFactory.HTTP11, http2);

        // Make a POST upgrade request from HTTP/1.1 to H2C.
        // We don't support upgrades with request content because
        // the application would need to read the request content in
        // HTTP/1.1 format but write the response in HTTP/2 format.
        byte[] bytes = new byte[1024 * 1024];
        new Random().nextBytes(bytes);
        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .method(HttpMethod.POST)
            .headers(headers -> headers
                .put(HttpHeader.UPGRADE, "h2c")
                .put(HttpHeader.HTTP2_SETTINGS, "")
                .put(HttpHeader.CONNECTION, "Upgrade, HTTP2-Settings"))
            .body(new BytesRequestContent(bytes))
            .timeout(5, TimeUnit.SECONDS)
            .send(new BufferingResponseListener(bytes.length)
            {
                @Override
                public void onComplete(Result result)
                {
                    assertTrue(result.isSucceeded());
                    assertArrayEquals(bytes, getContent());
                    latch.countDown();
                }
            });

        assertTrue(latch.await(15, TimeUnit.SECONDS));

        // Check that we did not upgrade.
        assertEquals(1, client.getDestinations().size());
    }

    @Test
    public void testHTTP11UpgradeToH2CFailedNoHTTP2Settings() throws Exception
    {
        startServer(this::h1H2C, new EmptyServerHandler());
        ClientConnector clientConnector = new ClientConnector();
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);
        startClient(clientConnector, HttpClientConnectionFactory.HTTP11, http2);

        // The upgrade request is missing the required HTTP2-Settings header.
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .headers(headers -> headers.add(HttpHeader.UPGRADE, "h2c"))
            .headers(headers -> headers.add(HttpHeader.CONNECTION, "Upgrade"))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.BAD_REQUEST_400, response.getStatus());
    }

    @Test
    public void testHTTP11UpgradeToH2CFailedServerClose() throws Exception
    {
        startServer(this::h1H2C, new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                request.getConnectionMetaData().getConnection().getEndPoint().close();
                callback.succeeded();
            }
        });
        ClientConnector clientConnector = new ClientConnector();
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);
        startClient(clientConnector, HttpClientConnectionFactory.HTTP11, http2);

        // Make an upgrade request from HTTP/1.1 to H2C.
        CountDownLatch latch = new CountDownLatch(1);
        client.newRequest("localhost", connector.getLocalPort())
            .headers(headers -> headers
                .put(HttpHeader.UPGRADE, "h2c")
                .put(HttpHeader.HTTP2_SETTINGS, "")
                .put(HttpHeader.CONNECTION, "Upgrade, HTTP2-Settings"))
            .send(result ->
            {
                if (result.isFailed())
                    latch.countDown();
            });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testClientWithALPNServerWithoutALPN() throws Exception
    {
        startServer(this::sslH1H2C, new EmptyServerHandler());
        ClientConnector clientConnector = new ClientConnector();
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info http2 = new ClientConnectionFactoryOverHTTP2.HTTP2(http2Client);
        startClient(clientConnector, HttpClientConnectionFactory.HTTP11, http2);

        // Make a request without explicit version, so ALPN is used on the client.
        // Since the server does not support ALPN, the first protocol is used.
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .scheme(HttpScheme.HTTPS.asString())
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
    }
}
