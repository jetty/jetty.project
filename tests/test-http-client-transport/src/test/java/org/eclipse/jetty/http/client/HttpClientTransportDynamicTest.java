//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http.client;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http2.client.HTTP2Client;
import org.eclipse.jetty.http2.client.http.ClientConnectionFactoryOverHTTP2;
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.ProxyConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.client.ProxyProtocolClientConnectionFactory.V1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        sslContextFactory.setKeyStorePassword("storepwd");
        // The mandatory HTTP/2 cipher.
        sslContextFactory.setIncludeCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
    }

    @Test
    public void testClearTextHTTP1() throws Exception
    {
        startServer(this::h1H2C, new EmptyServerHandler());

        HttpClientConnectionFactory.Info h1c = HttpClientConnectionFactory.HTTP11;
        client = new HttpClient(new HttpClientTransportDynamic(new ClientConnector(), h1c));
        client.start();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testClearTextHTTP2() throws Exception
    {
        startServer(this::h1H2C, new EmptyServerHandler());

        // TODO: why do we need HTTP2Client? we only use it for configuration,
        //  so the configuration can instead be moved to the CCF?
        ClientConnector clientConnector = new ClientConnector();
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info h2c = new ClientConnectionFactoryOverHTTP2.H2C(http2Client);
        client = new HttpClient(new HttpClientTransportDynamic(clientConnector, h2c));
        client.addBean(http2Client);
        client.start();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
//                .version(HttpVersion.HTTP_2)
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
        ClientConnectionFactory.Info h2c = new ClientConnectionFactoryOverHTTP2.H2C(http2Client);
        HttpClientTransportDynamic transport = new HttpClientTransportDynamic(clientConnector, h1, h2c)
        {
            @Override
            public Origin newOrigin(HttpRequest request)
            {
                // Use prior-knowledge, i.e. negotiate==false.
                List<String> protocols = HttpVersion.HTTP_2 == request.getVersion() ? h2c.getProtocols() : h1.getProtocols();
                return new Origin(request.getScheme(), request.getHost(), request.getPort(), request.getTag(), new Origin.Protocol(protocols, false));
            }
        };
        client = new HttpClient(transport);
        client.addBean(http2Client);
        client.start();

        // Make a HTTP/1.1 request.
        ContentResponse h1cResponse = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scheme.asString())
            .version(HttpVersion.HTTP_1_1)
            .send();
        assertEquals(HttpStatus.OK_200, h1cResponse.getStatus());

        // Make a HTTP/2 request.
        ContentResponse h2cResponse = client.newRequest("localhost", connector.getLocalPort())
            .scheme(scheme.asString())
            .version(HttpVersion.HTTP_2)
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
        clientConnector.setSslContextFactory(newClientSslContextFactory());
        HttpClientConnectionFactory.Info h1 = HttpClientConnectionFactory.HTTP11;
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info h2 = new ClientConnectionFactoryOverHTTP2.H2(http2Client);
        client = new HttpClient(new HttpClientTransportDynamic(clientConnector, h1, h2));
        client.addBean(http2Client);
        client.start();

        // Make a request, should be HTTP/1.1 because of the order of protocols on server.
        ContentResponse h1cResponse = client.newRequest("localhost", connector.getLocalPort())
            .scheme("https")
            .send();
        assertEquals(HttpStatus.OK_200, h1cResponse.getStatus());

        // Now clearly specify HTTP/2 in the client.
        ContentResponse h2cResponse = client.newRequest("localhost", connector.getLocalPort())
            .scheme("https")
            .version(HttpVersion.HTTP_2)
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
        clientConnector.setSslContextFactory(newClientSslContextFactory());
        HttpClientConnectionFactory.Info h1 = HttpClientConnectionFactory.HTTP11;
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info h2 = new ClientConnectionFactoryOverHTTP2.H2(http2Client);
        client = new HttpClient(new HttpClientTransportDynamic(clientConnector, h2, h1));
        client.addBean(http2Client);
        client.start();

        // The client prefers h2 over h1, and use of TLS and ALPN will allow the fallback to h1.
        ContentResponse h1cResponse = client.newRequest("localhost", connector.getLocalPort())
            .scheme("https")
            .send();
        assertEquals(HttpStatus.OK_200, h1cResponse.getStatus());
    }

    @Test
    public void testServerOnlySpeaksClearTextHTTP11ClientFailsHTTP2() throws Exception
    {
        startServer(this::h1, new EmptyServerHandler());

        ClientConnector clientConnector = new ClientConnector();
        HttpClientConnectionFactory.Info h1 = HttpClientConnectionFactory.HTTP11;
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info h2c = new ClientConnectionFactoryOverHTTP2.H2C(http2Client);
        client = new HttpClient(new HttpClientTransportDynamic(clientConnector, h1, h2c));
        client.addBean(http2Client);
        client.start();

        // The client forces HTTP/2, but the server cannot speak it, so the request fails.
        // There is no fallback to HTTP/1 because the protocol version is set explicitly.
        assertThrows(ExecutionException.class, () -> client.newRequest("localhost", connector.getLocalPort())
            .version(HttpVersion.HTTP_2)
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

        startServer(this::proxyH1H2C, new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setContentType(MimeTypes.Type.TEXT_PLAIN.asString());
                response.getOutputStream().print(request.getRemotePort());
            }
        });

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(newClientSslContextFactory());
        ClientConnectionFactory.Info h1 = HttpClientConnectionFactory.HTTP11;

        client = new HttpClient(new HttpClientTransportDynamic(clientConnector, h1));
        client.start();

        // Simulate a proxy request to the server.
        HttpRequest proxyRequest1 = (HttpRequest)client.newRequest("localhost", connector.getLocalPort());
        // Map the proxy request to client IP:port.
        int clientPort1 = ThreadLocalRandom.current().nextInt(1024, 65536);
        proxyRequest1.tag(new V1.Tag("localhost", clientPort1));
        ContentResponse proxyResponse1 = proxyRequest1.send();
        assertEquals(String.valueOf(clientPort1), proxyResponse1.getContentAsString());

        // Simulate another request to the server, from a different client port.
        HttpRequest proxyRequest2 = (HttpRequest)client.newRequest("localhost", connector.getLocalPort());
        int clientPort2 = ThreadLocalRandom.current().nextInt(1024, 65536);
        proxyRequest2.tag(new V1.Tag("localhost", clientPort2));
        ContentResponse proxyResponse2 = proxyRequest2.send();
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
        clientConnector.setSslContextFactory(newClientSslContextFactory());
        HttpClientConnectionFactory.Info h1 = HttpClientConnectionFactory.HTTP11;
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info h2c = new ClientConnectionFactoryOverHTTP2.H2C(http2Client);
        ClientConnectionFactory.Info h2 = new ClientConnectionFactoryOverHTTP2.H2(http2Client);
        client = new HttpClient(new HttpClientTransportDynamic(clientConnector, h2, h1, h2c));
        client.addBean(http2Client);
        client.start();

        // Make a clear-text request using HTTP/1.1.
        ContentResponse h1cResponse = client.newRequest("localhost", clearConnector.getLocalPort())
            .send();
        assertEquals(HttpStatus.OK_200, h1cResponse.getStatus());

        // Make a clear-text request using HTTP/2.
        ContentResponse h2cResponse = client.newRequest("localhost", clearConnector.getLocalPort())
            .version(HttpVersion.HTTP_2)
            .send();
        assertEquals(HttpStatus.OK_200, h2cResponse.getStatus());

        // Make an encrypted request without specifying the protocol.
        // Because the server prefers h2, this request will be HTTP/2, but will
        // generate a different destination than an explicit HTTP/2 request (like below).
        ContentResponse h1Response = client.newRequest("localhost", connector.getLocalPort())
            .scheme("https")
            .send();
        assertEquals(HttpStatus.OK_200, h1Response.getStatus());

        // Make an encrypted request using explicitly HTTP/2.
        ContentResponse h2Response = client.newRequest("localhost", connector.getLocalPort())
            .scheme("https")
            .version(HttpVersion.HTTP_2)
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
}
