//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.alpn.server.ALPNServerConnectionFactory;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpRequest;
import org.eclipse.jetty.client.MultiplexHttpDestination;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.client.http.HttpClientConnectionFactory;
import org.eclipse.jetty.client.proxy.ProxyProtocolClientConnectionFactory;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

public class HttpClientTransportDynamicTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    public void startServer(Function<Server, ServerConnector> connectorFn, Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        serverThreads.setDetailedDump(true);
        server = new Server(serverThreads);
        connector = connectorFn.apply(server);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    private ServerConnector h1_h2c(Server server)
    {
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        HttpConnectionFactory h1 = new HttpConnectionFactory(httpConfiguration);
        HTTP2CServerConnectionFactory h2c = new HTTP2CServerConnectionFactory(httpConfiguration);
        return new ServerConnector(server, 1, 1, h1, h2c);
    }

    private ServerConnector ssl_alpn_h1(Server server)
    {
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        HttpConnectionFactory h1 = new HttpConnectionFactory(httpConfiguration);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h1.getProtocol());
        SslContextFactory sslContextFactory = newSslContextFactory(false);
        return new ServerConnector(server, 1, 1, AbstractConnectionFactory.getFactories(sslContextFactory, alpn, h1));
    }

    private ServerConnector ssl_h1_h2c(Server server)
    {
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        HttpConnectionFactory h1 = new HttpConnectionFactory(httpConfiguration);
        HTTP2CServerConnectionFactory h2c = new HTTP2CServerConnectionFactory(httpConfiguration);
        SslContextFactory sslContextFactory = newSslContextFactory(false);
        // No ALPN.
        return new ServerConnector(server, 1, 1, AbstractConnectionFactory.getFactories(sslContextFactory, h1, h2c));
    }

    private ServerConnector ssl_alpn_h1_h2(Server server)
    {
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        HttpConnectionFactory h1 = new HttpConnectionFactory(httpConfiguration);
        ALPNServerConnectionFactory alpn = new ALPNServerConnectionFactory();
        alpn.setDefaultProtocol(h1.getProtocol());
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(httpConfiguration);
        SslContextFactory sslContextFactory = newSslContextFactory(false);
        return new ServerConnector(server, 1, 1, AbstractConnectionFactory.getFactories(sslContextFactory, alpn, h1, h2));
    }

    private ServerConnector proxy_h1_h2c(Server server)
    {
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        HttpConnectionFactory h1 = new HttpConnectionFactory(httpConfiguration);
        ProxyConnectionFactory proxy = new ProxyConnectionFactory(h1.getProtocol());
        return new ServerConnector(server, 1, 1, proxy, h1);
    }

    @AfterEach
    public void stop() throws Exception
    {
        if (server != null)
            server.stop();
        if (client != null)
            client.stop();
    }

    private SslContextFactory newSslContextFactory(boolean client)
    {
        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        sslContextFactory.setKeyStorePassword("storepwd");
        // The mandatory HTTP/2 cipher.
        sslContextFactory.setIncludeCipherSuites("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        if (client)
            sslContextFactory.setEndpointIdentificationAlgorithm(null);
        return sslContextFactory;
    }

    @Test
    public void testClearTextHTTP1() throws Exception
    {
        startServer(this::h1_h2c, new EmptyServerHandler());

        HttpClientConnectionFactory.Info h1c = HttpClientConnectionFactory.HTTP;
        client = new HttpClient(new HttpClientTransportDynamic(new ClientConnector(), h1c));
        client.start();
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
                .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testClearTextHTTP2() throws Exception
    {
        startServer(this::h1_h2c, new EmptyServerHandler());

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
        startServer(this::h1_h2c, new EmptyServerHandler());
        testProtocolSelection(HttpScheme.HTTP);
    }

    @Test
    public void testEncryptedProtocolSelectionWithoutNegotiation() throws Exception
    {
        startServer(this::ssl_h1_h2c, new EmptyServerHandler());
        testProtocolSelection(HttpScheme.HTTPS);
    }

    private void testProtocolSelection(HttpScheme scheme) throws Exception
    {
        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(newSslContextFactory(true));
        HttpClientConnectionFactory.Info h1 = HttpClientConnectionFactory.HTTP;
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info h2c = new ClientConnectionFactoryOverHTTP2.H2C(http2Client);
        HttpClientTransportDynamic transport = new HttpClientTransportDynamic(clientConnector, h1, h2c)
        {
            @Override
            public HttpDestination.Protocol getProtocol(HttpRequest request)
            {
                // Use prior-knowledge HTTP/2 if the application requests so.
                if (HttpVersion.HTTP_2 == request.getVersion())
                    return new HttpDestination.Protocol(h2c.getProtocols(), false);
                // Use the default protocol (i.e. the transport's first protocol).
                return null;
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
                .map(HttpDestination::getInfo)
                .map(HttpDestination.Info::getOrigin)
                .distinct()
                .count());
    }

    @Test
    public void testEncryptedProtocolSelectionWithNegotiation() throws Exception
    {
        startServer(this::ssl_alpn_h1_h2, new EmptyServerHandler());

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(newSslContextFactory(true));
        HttpClientConnectionFactory.Info h1 = HttpClientConnectionFactory.HTTP;
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
                .map(HttpDestination::getInfo)
                .map(HttpDestination.Info::getOrigin)
                .distinct()
                .count());
    }

    @Test
    public void testServerOnlySpeaksHTTP11ClientFallsBackToHTTP11() throws Exception
    {
        startServer(this::ssl_alpn_h1, new EmptyServerHandler());

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(newSslContextFactory(true));
        HttpClientConnectionFactory.Info h1 = HttpClientConnectionFactory.HTTP;
        HTTP2Client http2Client = new HTTP2Client(clientConnector);
        ClientConnectionFactory.Info h2 = new ClientConnectionFactoryOverHTTP2.H2(http2Client);
        client = new HttpClient(new HttpClientTransportDynamic(clientConnector, h1, h2));
        client.addBean(http2Client);
        client.start();

        ContentResponse h1cResponse = client.newRequest("localhost", connector.getLocalPort())
                .scheme("https")
                .send();
        assertEquals(HttpStatus.OK_200, h1cResponse.getStatus());
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

        startServer(this::proxy_h1_h2c, new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setContentType(MimeTypes.Type.TEXT_PLAIN.asString());
                response.getOutputStream().print(request.getRemotePort());
            }
        });

        ClientConnector clientConnector = new ClientConnector();
        clientConnector.setSslContextFactory(newSslContextFactory(true));
        HttpClientConnectionFactory.Info h1 = new ClientConnectionFactory.Info(List.of("http/1.1"), new HttpClientConnectionFactory());

        Map<HttpRequest, String> mapping = new ConcurrentHashMap<>();
        client = new HttpClient(new HttpClientTransportDynamic(clientConnector, h1)
        {
            @Override
            public HttpDestination.Protocol getProtocol(HttpRequest request)
            {
                String kind = mapping.remove(request);
                return new HttpDestination.Protocol(List.of("http/1.1"), false, kind);
            }

            @Override
            public HttpDestination newHttpDestination(HttpDestination.Info info)
            {
                // Here we want to wrap the destination with the PROXY
                // protocol, for a specific remote client socket address.
                HttpDestination.Protocol protocol = info.getProtocol();
                return new MultiplexHttpDestination(client, info, factory -> new ProxyProtocolClientConnectionFactory(factory, () ->
                {
                    String[] address = protocol.getKind().split(":");
                    return new InetSocketAddress(address[0], Integer.parseInt(address[1]));
                }));
            }
        });
        client.start();

        // Simulate a proxy request to the server.
        HttpRequest proxyRequest1 = (HttpRequest)client.newRequest("localhost", connector.getLocalPort());
        // Map the proxy request to client IP:port.
        int clientPort1 = ThreadLocalRandom.current().nextInt(1024, 65536);
        mapping.put(proxyRequest1, "localhost:" + clientPort1);
        ContentResponse proxyResponse1 = proxyRequest1.send();
        assertEquals(String.valueOf(clientPort1), proxyResponse1.getContentAsString());

        // Simulate another request to the server, from a different client port.
        HttpRequest proxyRequest2 = (HttpRequest)client.newRequest("localhost", connector.getLocalPort());
        int clientPort2 = ThreadLocalRandom.current().nextInt(1024, 65536);
        mapping.put(proxyRequest2, "localhost:" + clientPort2);
        ContentResponse proxyResponse2 = proxyRequest2.send();
        assertEquals(String.valueOf(clientPort2), proxyResponse2.getContentAsString());

        // We must have 2 different destinations with the same origin.
        List<Destination> destinations = client.getDestinations();
        assertEquals(2, destinations.size());
        assertEquals(1, destinations.stream()
                .map(HttpDestination.class::cast)
                .map(HttpDestination::getInfo)
                .map(HttpDestination.Info::getOrigin)
                .distinct()
                .count());
    }
}
