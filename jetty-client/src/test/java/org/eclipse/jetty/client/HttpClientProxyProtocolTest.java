//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.ProxyConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.client.ProxyProtocolClientConnectionFactory.V1;
import static org.eclipse.jetty.client.ProxyProtocolClientConnectionFactory.V2;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HttpClientProxyProtocolTest
{
    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    private void startServer(Handler handler) throws Exception
    {
        QueuedThreadPool serverThreads = new QueuedThreadPool();
        serverThreads.setName("server");
        server = new Server(serverThreads);
        HttpConnectionFactory http = new HttpConnectionFactory();
        ProxyConnectionFactory proxy = new ProxyConnectionFactory(http.getProtocol());
        connector = new ServerConnector(server, 1, 1, proxy, http);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    private void startClient() throws Exception
    {
        QueuedThreadPool clientThreads = new QueuedThreadPool();
        clientThreads.setName("client");
        client = new HttpClient();
        client.setExecutor(clientThreads);
        client.setRemoveIdleDestinations(false);
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (server != null)
            server.stop();
        if (client != null)
            client.stop();
    }

    @Test
    public void testClientProxyProtocolV1() throws Exception
    {
        startServer(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setContentType(MimeTypes.Type.TEXT_PLAIN.asString());
                response.getOutputStream().print(request.getRemotePort());
            }
        });
        startClient();

        int serverPort = connector.getLocalPort();

        int clientPort = ThreadLocalRandom.current().nextInt(1024, 65536);
        V1.Tag tag = new V1.Tag("127.0.0.1", clientPort);

        ContentResponse response = client.newRequest("localhost", serverPort)
            .tag(tag)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(String.valueOf(clientPort), response.getContentAsString());
    }

    @Test
    public void testClientProxyProtocolV1Unknown() throws Exception
    {
        startServer(new EmptyServerHandler());
        startClient();

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .tag(V1.Tag.UNKNOWN)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testClientProxyProtocolV2() throws Exception
    {
        startServer(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setContentType(MimeTypes.Type.TEXT_PLAIN.asString());
                response.getOutputStream().print(request.getRemotePort());
            }
        });
        startClient();

        int serverPort = connector.getLocalPort();

        int clientPort = ThreadLocalRandom.current().nextInt(1024, 65536);
        V2.Tag tag = new V2.Tag("127.0.0.1", clientPort);

        ContentResponse response = client.newRequest("localhost", serverPort)
            .tag(tag)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(String.valueOf(clientPort), response.getContentAsString());
    }

    @Test
    public void testClientProxyProtocolV2Local() throws Exception
    {
        startServer(new EmptyServerHandler());
        startClient();

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .tag(V2.Tag.LOCAL)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }

    @Test
    public void testClientProxyProtocolV2WithVectors() throws Exception
    {
        String tlsVersion = "TLSv1.3";
        byte[] tlsVersionBytes = tlsVersion.getBytes(StandardCharsets.US_ASCII);
        startServer(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                EndPoint endPoint = jettyRequest.getHttpChannel().getEndPoint();
                assertTrue(endPoint instanceof ProxyConnectionFactory.ProxyEndPoint);
                ProxyConnectionFactory.ProxyEndPoint proxyEndPoint = (ProxyConnectionFactory.ProxyEndPoint)endPoint;
                if (target.equals("/tls_version"))
                    assertEquals(tlsVersion, proxyEndPoint.getAttribute(ProxyConnectionFactory.TLS_VERSION));
                response.setContentType(MimeTypes.Type.TEXT_PLAIN.asString());
                response.getOutputStream().print(request.getRemotePort());
            }
        });
        startClient();

        int serverPort = connector.getLocalPort();

        int clientPort = ThreadLocalRandom.current().nextInt(1024, 65536);
        int typeTLS = 0x20;
        byte[] dataTLS = new byte[1 + 4 + (1 + 2 + tlsVersionBytes.length)];
        dataTLS[0] = 0x01; // CLIENT_SSL
        dataTLS[5] = 0x21; // SUBTYPE_SSL_VERSION
        dataTLS[6] = 0x00; // Length, hi byte
        dataTLS[7] = (byte)tlsVersionBytes.length; // Length, lo byte
        System.arraycopy(tlsVersionBytes, 0, dataTLS, 8, tlsVersionBytes.length);
        V2.Tag.TLV tlv = new V2.Tag.TLV(typeTLS, dataTLS);
        V2.Tag tag = new V2.Tag("127.0.0.1", clientPort, Collections.singletonList(tlv));

        ContentResponse response = client.newRequest("localhost", serverPort)
            .path("/tls_version")
            .tag(tag)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(String.valueOf(clientPort), response.getContentAsString());

        // Make another request with the same address information, but different TLV.
        V2.Tag.TLV tlv2 = new V2.Tag.TLV(0x01, "http/1.1".getBytes(StandardCharsets.UTF_8));
        V2.Tag tag2 = new V2.Tag("127.0.0.1", clientPort, Collections.singletonList(tlv2));
        response = client.newRequest("localhost", serverPort)
            .tag(tag2)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(String.valueOf(clientPort), response.getContentAsString());

        // Make sure the two TLVs created two destinations.
        assertEquals(2, client.getDestinations().size());
    }

    @Test
    public void testProxyProtocolWrappingHTTPProxy() throws Exception
    {
        startServer(new EmptyServerHandler()
        {
            @Override
            protected void service(String target, Request jettyRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                response.setContentType(MimeTypes.Type.TEXT_PLAIN.asString());
                response.getOutputStream().print(request.getRemotePort());
            }
        });
        startClient();

        int proxyPort = connector.getLocalPort();
        int serverPort = proxyPort + 1; // Any port will do.
        client.getProxyConfiguration().getProxies().add(new HttpProxy("localhost", proxyPort));

        // We are simulating to be a HttpClient inside a proxy.
        // The server is configured with the PROXY protocol to know the socket address of clients.

        // The proxy receives a request from the client, and it extracts the client address.
        int clientPort = ThreadLocalRandom.current().nextInt(1024, 65536);
        V1.Tag tag = new V1.Tag("127.0.0.1", clientPort);

        // The proxy maps the client address, then sends the request.
        ContentResponse response = client.newRequest("localhost", serverPort)
            .tag(tag)
            .header(HttpHeader.CONNECTION, "close")
            .send();

        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(String.valueOf(clientPort), response.getContentAsString());
        List<Destination> destinations = client.getDestinations();
        assertEquals(1, destinations.size());
        HttpDestination destination = (HttpDestination)destinations.get(0);
        assertTrue(destination.getConnectionPool().isEmpty());

        // The previous connection has been closed.
        // Make another request from the same client address.
        response = client.newRequest("localhost", serverPort)
            .tag(tag)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(String.valueOf(clientPort), response.getContentAsString());
        destinations = client.getDestinations();
        assertEquals(1, destinations.size());
        assertSame(destination, destinations.get(0));

        // Make another request from a different client address.
        int clientPort2 = clientPort + 1;
        V1.Tag tag2 = new V1.Tag("127.0.0.1", clientPort2);
        response = client.newRequest("localhost", serverPort)
            .tag(tag2)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(String.valueOf(clientPort2), response.getContentAsString());
        destinations = client.getDestinations();
        assertEquals(2, destinations.size());
    }
}
