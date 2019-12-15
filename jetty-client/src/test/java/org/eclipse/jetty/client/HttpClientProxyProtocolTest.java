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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
        V1.Info info = new V1.Info("TCP4", "127.0.0.1", clientPort, "127.0.0.1", serverPort);

        ContentResponse response = client.newRequest("localhost", serverPort)
            .tag(info)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(String.valueOf(clientPort), response.getContentAsString());
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
        V2.Info info = new V2.Info(V2.Info.Command.PROXY, V2.Info.Family.INET4, V2.Info.Protocol.STREAM, "127.0.0.1", clientPort, "127.0.0.1", serverPort);

        ContentResponse response = client.newRequest("localhost", serverPort)
            .tag(info)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(String.valueOf(clientPort), response.getContentAsString());
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
                assertEquals(tlsVersion, proxyEndPoint.getAttribute(ProxyConnectionFactory.TLS_VERSION));
                response.setContentType(MimeTypes.Type.TEXT_PLAIN.asString());
                response.getOutputStream().print(request.getRemotePort());
            }
        });
        startClient();

        int serverPort = connector.getLocalPort();

        int clientPort = ThreadLocalRandom.current().nextInt(1024, 65536);
        V2.Info info = new V2.Info(V2.Info.Command.PROXY, V2.Info.Family.INET4, V2.Info.Protocol.STREAM, "127.0.0.1", clientPort, "127.0.0.1", serverPort);
        int typeTLS = 0x20;
        byte[] dataTLS = new byte[1 + 4 + (1 + 2 + tlsVersionBytes.length)];
        dataTLS[0] = 0x01; // CLIENT_SSL
        dataTLS[5] = 0x21; // SUBTYPE_SSL_VERSION
        dataTLS[6] = 0x00; // Length, hi byte
        dataTLS[7] = (byte)tlsVersionBytes.length; // Length, lo byte
        System.arraycopy(tlsVersionBytes, 0, dataTLS, 8, tlsVersionBytes.length);
        info.put(typeTLS, dataTLS);

        ContentResponse response = client.newRequest("localhost", serverPort)
            .tag(info)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(String.valueOf(clientPort), response.getContentAsString());
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
        V1.Info info = new V1.Info("TCP4", "127.0.0.1", clientPort, "127.0.0.1", serverPort);

        // The proxy maps the client address, then sends the request.
        ContentResponse response = client.newRequest("localhost", serverPort)
            .tag(info)
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
            .tag(info)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(String.valueOf(clientPort), response.getContentAsString());
        destinations = client.getDestinations();
        assertEquals(1, destinations.size());
        assertSame(destination, destinations.get(0));

        // Make another request from a different client address.
        int clientPort2 = clientPort + 1;
        V1.Info info2 = new V1.Info("TCP4", "127.0.0.1", clientPort2, "127.0.0.1", serverPort);
        response = client.newRequest("localhost", serverPort)
            .tag(info2)
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(String.valueOf(clientPort2), response.getContentAsString());
        destinations = client.getDestinations();
        assertEquals(2, destinations.size());
    }
}
