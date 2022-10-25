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

package org.eclipse.jetty.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.AbstractConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class HttpClientCustomProxyTest
{
    public static final byte[] CAFE_BABE = new byte[]{(byte)0xCA, (byte)0xFE, (byte)0xBA, (byte)0xBE};

    private Server server;
    private ServerConnector connector;
    private HttpClient client;

    public void prepare(Handler handler) throws Exception
    {
        server = new Server();
        connector = new ServerConnector(server, new CAFEBABEServerConnectionFactory(new HttpConnectionFactory()));
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();

        QueuedThreadPool executor = new QueuedThreadPool();
        executor.setName(executor.getName() + "-client");
        client = new HttpClient();
        client.setExecutor(executor);
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
    public void testCustomProxy() throws Exception
    {
        final String serverHost = "server";
        final int status = HttpStatus.NO_CONTENT_204;
        prepare(new AbstractHandler()
        {
            @Override
            public void handle(String target, org.eclipse.jetty.server.Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                if (serverHost.equals(request.getServerName()))
                    response.setStatus(status);
                else
                    response.setStatus(HttpServletResponse.SC_NOT_ACCEPTABLE);
            }
        });

        // Setup the custom proxy
        int proxyPort = connector.getLocalPort();
        int serverPort = proxyPort + 1; // Any port will do for these tests - just not the same as the proxy
        client.getProxyConfiguration().addProxy(new CAFEBABEProxy(new Origin.Address("localhost", proxyPort), false));

        ContentResponse response = client.newRequest(serverHost, serverPort)
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertEquals(status, response.getStatus());
    }

    private class CAFEBABEProxy extends ProxyConfiguration.Proxy
    {
        private CAFEBABEProxy(Origin.Address address, boolean secure)
        {
            super(address, secure, null, null);
        }

        @Override
        public ClientConnectionFactory newClientConnectionFactory(ClientConnectionFactory connectionFactory)
        {
            return new CAFEBABEClientConnectionFactory(connectionFactory);
        }
    }

    private class CAFEBABEClientConnectionFactory implements ClientConnectionFactory
    {
        private final ClientConnectionFactory connectionFactory;

        private CAFEBABEClientConnectionFactory(ClientConnectionFactory connectionFactory)
        {
            this.connectionFactory = connectionFactory;
        }

        @Override
        public org.eclipse.jetty.io.Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
        {
            HttpDestination destination = (HttpDestination)context.get(HttpClientTransport.HTTP_DESTINATION_CONTEXT_KEY);
            Executor executor = destination.getHttpClient().getExecutor();
            CAFEBABEConnection connection = new CAFEBABEConnection(endPoint, executor, connectionFactory, context);
            return customize(connection, context);
        }
    }

    private class CAFEBABEConnection extends AbstractConnection implements Callback
    {
        private final ClientConnectionFactory connectionFactory;
        private final Map<String, Object> context;

        public CAFEBABEConnection(EndPoint endPoint, Executor executor, ClientConnectionFactory connectionFactory, Map<String, Object> context)
        {
            super(endPoint, executor);
            this.connectionFactory = connectionFactory;
            this.context = context;
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            getEndPoint().write(this, ByteBuffer.wrap(CAFE_BABE));
        }

        @Override
        public void succeeded()
        {
            fillInterested();
        }

        @Override
        public void failed(Throwable x)
        {
            close();
        }

        @Override
        public void onFillable()
        {
            try
            {
                ByteBuffer buffer = BufferUtil.allocate(4);
                int filled = getEndPoint().fill(buffer);
                assertEquals(4, filled);
                assertArrayEquals(CAFE_BABE, buffer.array());

                // We are good, upgrade the connection
                getEndPoint().upgrade(connectionFactory.newConnection(getEndPoint(), context));
            }
            catch (Throwable x)
            {
                close();
                @SuppressWarnings("unchecked")
                Promise<Connection> promise = (Promise<Connection>)context.get(HttpClientTransport.HTTP_CONNECTION_PROMISE_CONTEXT_KEY);
                promise.failed(x);
            }
        }
    }

    private class CAFEBABEServerConnectionFactory extends AbstractConnectionFactory
    {
        private final org.eclipse.jetty.server.ConnectionFactory connectionFactory;

        private CAFEBABEServerConnectionFactory(org.eclipse.jetty.server.ConnectionFactory connectionFactory)
        {
            super("cafebabe");
            this.connectionFactory = connectionFactory;
        }

        @Override
        public org.eclipse.jetty.io.Connection newConnection(Connector connector, EndPoint endPoint)
        {
            return new CAFEBABEServerConnection(connector, endPoint, connectionFactory);
        }
    }

    private class CAFEBABEServerConnection extends AbstractConnection implements Callback
    {
        private final org.eclipse.jetty.server.ConnectionFactory connectionFactory;

        public CAFEBABEServerConnection(Connector connector, EndPoint endPoint, org.eclipse.jetty.server.ConnectionFactory connectionFactory)
        {
            super(endPoint, connector.getExecutor());
            this.connectionFactory = connectionFactory;
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            fillInterested();
        }

        @Override
        public void onFillable()
        {
            try
            {
                ByteBuffer buffer = BufferUtil.allocate(4);
                int filled = getEndPoint().fill(buffer);
                assertEquals(4, filled);
                assertArrayEquals(CAFE_BABE, buffer.array());
                getEndPoint().write(this, buffer);
            }
            catch (Throwable x)
            {
                close();
            }
        }

        @Override
        public void succeeded()
        {
            // We are good, upgrade the connection
            getEndPoint().upgrade(connectionFactory.newConnection(connector, getEndPoint()));
        }

        @Override
        public void failed(Throwable x)
        {
            close();
        }
    }
}
