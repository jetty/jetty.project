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
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.http.HttpClientTransportOverHTTP;
import org.eclipse.jetty.client.util.FormContentProvider;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.NetworkTrafficListener;
import org.eclipse.jetty.io.NetworkTrafficSocketChannelEndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.NetworkTrafficServerConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Fields;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class NetworkTrafficListenerTest
{
    private static final String END_OF_CONTENT = "~";

    private Server server;
    private NetworkTrafficServerConnector connector;
    private NetworkTrafficHttpClient client;

    private void start(Handler handler) throws Exception
    {
        startServer(handler);
        startClient();
    }

    private void startServer(Handler handler) throws Exception
    {
        server = new Server();
        connector = new NetworkTrafficServerConnector(server);
        connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendDateHeader(false);
        connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration().setSendServerVersion(false);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    private void startClient() throws Exception
    {
        client = new NetworkTrafficHttpClient(new ArrayList<>());
        client.start();
    }

    @AfterEach
    public void dispose() throws Exception
    {
        if (client != null)
            client.stop();
        if (server != null)
            server.stop();
    }

    @Test
    public void testOpenedClosedAreInvoked() throws Exception
    {
        startServer(null);

        CountDownLatch openedLatch = new CountDownLatch(1);
        CountDownLatch closedLatch = new CountDownLatch(1);
        connector.addNetworkTrafficListener(new NetworkTrafficListener()
        {
            public volatile Socket socket;

            @Override
            public void opened(Socket socket)
            {
                this.socket = socket;
                openedLatch.countDown();
            }

            @Override
            public void closed(Socket socket)
            {
                if (this.socket == socket)
                    closedLatch.countDown();
            }
        });
        int port = connector.getLocalPort();

        // Connect to the server
        try (Socket ignored = new Socket("localhost", port))
        {
            assertTrue(openedLatch.await(10, TimeUnit.SECONDS));
        }
        assertTrue(closedLatch.await(10, TimeUnit.SECONDS));
    }

    @Test
    public void testTrafficWithNoResponseContentOnNonPersistentConnection() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String uri, Request request, HttpServletRequest servletRequest, HttpServletResponse servletResponse)
            {
                request.setHandled(true);
            }
        });

        AtomicReference<String> serverIncoming = new AtomicReference<>("");
        CountDownLatch serverIncomingLatch = new CountDownLatch(1);
        AtomicReference<String> serverOutgoing = new AtomicReference<>("");
        CountDownLatch serverOutgoingLatch = new CountDownLatch(1);
        connector.addNetworkTrafficListener(new NetworkTrafficListener()
        {
            @Override
            public void incoming(Socket socket, ByteBuffer bytes)
            {
                serverIncoming.set(serverIncoming.get() + BufferUtil.toString(bytes, StandardCharsets.UTF_8));
                serverIncomingLatch.countDown();
            }

            @Override
            public void outgoing(Socket socket, ByteBuffer bytes)
            {
                serverOutgoing.set(serverOutgoing.get() + BufferUtil.toString(bytes, StandardCharsets.UTF_8));
                serverOutgoingLatch.countDown();
            }
        });

        AtomicReference<String> clientIncoming = new AtomicReference<>("");
        CountDownLatch clientIncomingLatch = new CountDownLatch(1);
        AtomicReference<String> clientOutgoing = new AtomicReference<>("");
        CountDownLatch clientOutgoingLatch = new CountDownLatch(1);
        client.listeners.add(new NetworkTrafficListener()
        {
            @Override
            public void outgoing(Socket socket, ByteBuffer bytes)
            {
                clientOutgoing.set(clientOutgoing.get() + BufferUtil.toString(bytes, StandardCharsets.UTF_8));
                clientOutgoingLatch.countDown();
            }

            @Override
            public void incoming(Socket socket, ByteBuffer bytes)
            {
                clientIncoming.set(clientIncoming.get() + BufferUtil.toString(bytes, StandardCharsets.UTF_8));
                clientIncomingLatch.countDown();
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .header(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE.asString())
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        assertTrue(clientOutgoingLatch.await(1, TimeUnit.SECONDS));
        assertTrue(serverIncomingLatch.await(1, TimeUnit.SECONDS));
        assertTrue(serverOutgoingLatch.await(1, TimeUnit.SECONDS));
        assertTrue(clientIncomingLatch.await(1, TimeUnit.SECONDS));
        assertEquals(clientOutgoing.get(), serverIncoming.get());
        assertEquals(serverOutgoing.get(), clientIncoming.get());
    }

    @Test
    public void testTrafficWithResponseContentOnPersistentConnection() throws Exception
    {
        String responseContent = "response_content" + END_OF_CONTENT;
        start(new AbstractHandler()
        {
            @Override
            public void handle(String uri, Request request, HttpServletRequest servletRequest, HttpServletResponse servletResponse) throws IOException
            {
                request.setHandled(true);
                ServletOutputStream output = servletResponse.getOutputStream();
                output.write(responseContent.getBytes(StandardCharsets.UTF_8));
            }
        });

        AtomicReference<String> serverIncoming = new AtomicReference<>("");
        CountDownLatch serverIncomingLatch = new CountDownLatch(1);
        AtomicReference<String> serverOutgoing = new AtomicReference<>("");
        CountDownLatch serverOutgoingLatch = new CountDownLatch(1);
        connector.addNetworkTrafficListener(new NetworkTrafficListener()
        {
            @Override
            public void incoming(Socket socket, ByteBuffer bytes)
            {
                serverIncoming.set(serverIncoming.get() + BufferUtil.toString(bytes, StandardCharsets.UTF_8));
                serverIncomingLatch.countDown();
            }

            @Override
            public void outgoing(Socket socket, ByteBuffer bytes)
            {
                serverOutgoing.set(serverOutgoing.get() + BufferUtil.toString(bytes, StandardCharsets.UTF_8));
                serverOutgoingLatch.countDown();
            }
        });

        AtomicReference<String> clientIncoming = new AtomicReference<>("");
        CountDownLatch clientIncomingLatch = new CountDownLatch(1);
        AtomicReference<String> clientOutgoing = new AtomicReference<>("");
        CountDownLatch clientOutgoingLatch = new CountDownLatch(1);
        client.listeners.add(new NetworkTrafficListener()
        {
            @Override
            public void outgoing(Socket socket, ByteBuffer bytes)
            {
                clientOutgoing.set(clientOutgoing.get() + BufferUtil.toString(bytes, StandardCharsets.UTF_8));
                clientOutgoingLatch.countDown();
            }

            @Override
            public void incoming(Socket socket, ByteBuffer bytes)
            {
                clientIncoming.set(clientIncoming.get() + BufferUtil.toString(bytes, StandardCharsets.UTF_8));
                clientIncomingLatch.countDown();
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort()).send();
        assertEquals(HttpStatus.OK_200, response.getStatus());
        assertEquals(responseContent, response.getContentAsString());

        assertTrue(clientOutgoingLatch.await(1, TimeUnit.SECONDS));
        assertTrue(serverIncomingLatch.await(1, TimeUnit.SECONDS));
        assertTrue(serverOutgoingLatch.await(1, TimeUnit.SECONDS));
        assertTrue(clientIncomingLatch.await(1, TimeUnit.SECONDS));
        assertEquals(clientOutgoing.get(), serverIncoming.get());
        assertEquals(serverOutgoing.get(), clientIncoming.get());
    }

    @Test
    public void testTrafficWithResponseContentChunkedOnPersistentConnection() throws Exception
    {
        String responseContent = "response_content";
        String responseChunk1 = responseContent.substring(0, responseContent.length() / 2);
        String responseChunk2 = responseContent.substring(responseContent.length() / 2);
        start(new AbstractHandler()
        {
            @Override
            public void handle(String uri, Request request, HttpServletRequest servletRequest, HttpServletResponse servletResponse) throws IOException
            {
                request.setHandled(true);
                ServletOutputStream output = servletResponse.getOutputStream();
                output.write(responseChunk1.getBytes(StandardCharsets.UTF_8));
                output.flush();
                output.write(responseChunk2.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        });

        AtomicReference<String> serverIncoming = new AtomicReference<>("");
        CountDownLatch serverIncomingLatch = new CountDownLatch(1);
        AtomicReference<String> serverOutgoing = new AtomicReference<>("");
        CountDownLatch serverOutgoingLatch = new CountDownLatch(1);
        connector.addNetworkTrafficListener(new NetworkTrafficListener()
        {
            @Override
            public void incoming(Socket socket, ByteBuffer bytes)
            {
                serverIncoming.set(serverIncoming.get() + BufferUtil.toString(bytes, StandardCharsets.UTF_8));
                serverIncomingLatch.countDown();
            }

            @Override
            public void outgoing(Socket socket, ByteBuffer bytes)
            {
                serverOutgoing.set(serverOutgoing.get() + BufferUtil.toString(bytes, StandardCharsets.UTF_8));
                if (serverOutgoing.get().endsWith("\r\n0\r\n\r\n"))
                    serverOutgoingLatch.countDown();
            }
        });

        AtomicReference<String> clientIncoming = new AtomicReference<>("");
        CountDownLatch clientIncomingLatch = new CountDownLatch(1);
        AtomicReference<String> clientOutgoing = new AtomicReference<>("");
        CountDownLatch clientOutgoingLatch = new CountDownLatch(1);
        client.listeners.add(new NetworkTrafficListener()
        {
            @Override
            public void outgoing(Socket socket, ByteBuffer bytes)
            {
                clientOutgoing.set(clientOutgoing.get() + BufferUtil.toString(bytes, StandardCharsets.UTF_8));
                clientOutgoingLatch.countDown();
            }

            @Override
            public void incoming(Socket socket, ByteBuffer bytes)
            {
                clientIncoming.set(clientIncoming.get() + BufferUtil.toString(bytes, StandardCharsets.UTF_8));
                if (clientIncoming.get().endsWith("\r\n0\r\n\r\n"))
                    clientIncomingLatch.countDown();
            }
        });

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort()).send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        assertTrue(clientOutgoingLatch.await(1, TimeUnit.SECONDS));
        assertTrue(serverIncomingLatch.await(1, TimeUnit.SECONDS));
        assertTrue(serverOutgoingLatch.await(1, TimeUnit.SECONDS));
        assertTrue(clientIncomingLatch.await(1, TimeUnit.SECONDS));
        assertEquals(clientOutgoing.get(), serverIncoming.get());
        assertEquals(serverOutgoing.get(), clientIncoming.get());
    }

    @Test
    public void testTrafficWithRequestContentWithResponseRedirectOnPersistentConnection() throws Exception
    {
        String location = "/redirect";
        start(new AbstractHandler()
        {
            @Override
            public void handle(String uri, Request request, HttpServletRequest servletRequest, HttpServletResponse servletResponse) throws IOException
            {
                request.setHandled(true);
                servletResponse.sendRedirect(location);
            }
        });

        AtomicReference<String> serverIncoming = new AtomicReference<>("");
        CountDownLatch serverIncomingLatch = new CountDownLatch(1);
        AtomicReference<String> serverOutgoing = new AtomicReference<>("");
        CountDownLatch serverOutgoingLatch = new CountDownLatch(1);
        connector.addNetworkTrafficListener(new NetworkTrafficListener()
        {
            @Override
            public void incoming(Socket socket, ByteBuffer bytes)
            {
                serverIncoming.set(serverIncoming.get() + BufferUtil.toString(bytes, StandardCharsets.UTF_8));
                serverIncomingLatch.countDown();
            }

            @Override
            public void outgoing(Socket socket, ByteBuffer bytes)
            {
                serverOutgoing.set(serverOutgoing.get() + BufferUtil.toString(bytes, StandardCharsets.UTF_8));
                serverOutgoingLatch.countDown();
            }
        });

        AtomicReference<String> clientIncoming = new AtomicReference<>("");
        CountDownLatch clientIncomingLatch = new CountDownLatch(1);
        AtomicReference<String> clientOutgoing = new AtomicReference<>("");
        CountDownLatch clientOutgoingLatch = new CountDownLatch(1);
        client.listeners.add(new NetworkTrafficListener()
        {
            @Override
            public void outgoing(Socket socket, ByteBuffer bytes)
            {
                clientOutgoing.set(clientOutgoing.get() + BufferUtil.toString(bytes, StandardCharsets.UTF_8));
                clientOutgoingLatch.countDown();
            }

            @Override
            public void incoming(Socket socket, ByteBuffer bytes)
            {
                clientIncoming.set(clientIncoming.get() + BufferUtil.toString(bytes, StandardCharsets.UTF_8));
                clientIncomingLatch.countDown();
            }
        });

        client.setFollowRedirects(false);
        Fields fields = new Fields();
        fields.put("a", "1");
        fields.put("b", "2");
        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .content(new FormContentProvider(fields))
            .send();
        assertEquals(HttpStatus.FOUND_302, response.getStatus());

        assertTrue(clientOutgoingLatch.await(1, TimeUnit.SECONDS));
        assertTrue(serverIncomingLatch.await(1, TimeUnit.SECONDS));
        assertTrue(serverOutgoingLatch.await(1, TimeUnit.SECONDS));
        assertTrue(clientIncomingLatch.await(1, TimeUnit.SECONDS));
        assertEquals(clientOutgoing.get(), serverIncoming.get());
        assertEquals(serverOutgoing.get(), clientIncoming.get());
    }

    @Test
    public void testTrafficWithBigRequestContentOnPersistentConnection() throws Exception
    {
        start(new AbstractHandler()
        {
            @Override
            public void handle(String uri, Request request, HttpServletRequest servletRequest, HttpServletResponse servletResponse) throws IOException
            {
                // Read and discard the request body to make the test more
                // reliable, otherwise there is a race between request body
                // upload and response download
                InputStream input = servletRequest.getInputStream();
                byte[] buffer = new byte[4096];
                while (true)
                {
                    int read = input.read(buffer);
                    if (read < 0)
                        break;
                }
                request.setHandled(true);
            }
        });

        AtomicReference<String> serverIncoming = new AtomicReference<>("");
        AtomicReference<String> serverOutgoing = new AtomicReference<>("");
        CountDownLatch serverOutgoingLatch = new CountDownLatch(1);
        connector.addNetworkTrafficListener(new NetworkTrafficListener()
        {
            @Override
            public void incoming(Socket socket, ByteBuffer bytes)
            {
                serverIncoming.set(serverIncoming.get() + BufferUtil.toString(bytes, StandardCharsets.UTF_8));
            }

            @Override
            public void outgoing(Socket socket, ByteBuffer bytes)
            {
                serverOutgoing.set(serverOutgoing.get() + BufferUtil.toString(bytes, StandardCharsets.UTF_8));
                serverOutgoingLatch.countDown();
            }
        });

        AtomicReference<String> clientIncoming = new AtomicReference<>("");
        CountDownLatch clientIncomingLatch = new CountDownLatch(1);
        AtomicReference<String> clientOutgoing = new AtomicReference<>("");
        client.listeners.add(new NetworkTrafficListener()
        {
            @Override
            public void outgoing(Socket socket, ByteBuffer bytes)
            {
                clientOutgoing.set(clientOutgoing.get() + BufferUtil.toString(bytes, StandardCharsets.UTF_8));
            }

            @Override
            public void incoming(Socket socket, ByteBuffer bytes)
            {
                clientIncoming.set(clientIncoming.get() + BufferUtil.toString(bytes, StandardCharsets.UTF_8));
                clientIncomingLatch.countDown();
            }
        });

        // Generate a large request content.
        String requestContent = "0123456789ABCDEF";
        for (int i = 0; i < 16; ++i)
        {
            requestContent += requestContent;
        }

        ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
            .content(new StringContentProvider(requestContent))
            .send();
        assertEquals(HttpStatus.OK_200, response.getStatus());

        assertTrue(serverOutgoingLatch.await(1, TimeUnit.SECONDS));
        assertTrue(clientIncomingLatch.await(1, TimeUnit.SECONDS));
        assertEquals(clientOutgoing.get(), serverIncoming.get());
        assertTrue(clientOutgoing.get().length() > requestContent.length());
        assertEquals(serverOutgoing.get(), clientIncoming.get());
    }

    private static class NetworkTrafficHttpClient extends HttpClient
    {
        private final List<NetworkTrafficListener> listeners;

        private NetworkTrafficHttpClient(List<NetworkTrafficListener> listeners)
        {
            super(new HttpClientTransportOverHTTP()
            {
                @Override
                protected SelectorManager newSelectorManager(HttpClient client)
                {
                    return new AbstractConnectorHttpClientTransport.ClientSelectorManager(client, getSelectors())
                    {
                        @Override
                        protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey key)
                        {
                            return new NetworkTrafficSocketChannelEndPoint(channel, selector, key, getScheduler(), client.getIdleTimeout(), listeners);
                        }
                    };
                }
            }, null);
            this.listeners = listeners;
        }
    }
}
