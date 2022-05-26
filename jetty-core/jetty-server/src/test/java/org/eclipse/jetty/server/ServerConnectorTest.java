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

package org.eclipse.jetty.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.net.BindException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ServerConnectorTest
{
    public static class ReuseInfoHandler extends Handler.Processor
    {
        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain");

            EndPoint endPoint = request.getConnectionMetaData().getConnection().getEndPoint();
            assertThat("Endpoint", endPoint, instanceOf(SocketChannelEndPoint.class));
            SocketChannelEndPoint channelEndPoint = (SocketChannelEndPoint)endPoint;
            Socket socket = channelEndPoint.getChannel().socket();
            ServerConnector connector = (ServerConnector)request.getConnectionMetaData().getConnector();

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            PrintWriter out = new PrintWriter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8));
            out.printf("connector.getReuseAddress() = %b%n", connector.getReuseAddress());

            try
            {
                Field fld = connector.getClass().getDeclaredField("_reuseAddress");
                assertThat("Field[_reuseAddress]", fld, notNullValue());
                fld.setAccessible(true);
                Object val = fld.get(connector);
                out.printf("connector._reuseAddress() = %b%n", val);
            }
            catch (Throwable t)
            {
                t.printStackTrace(out);
            }
            out.printf("socket.getReuseAddress() = %b%n", socket.getReuseAddress());
            out.flush();
            response.write(true, BufferUtil.toBuffer(buffer.toByteArray()), callback);
        }
    }

    private URI toServerURI(ServerConnector connector) throws URISyntaxException
    {
        String host = connector.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = connector.getLocalPort();
        return new URI(String.format("http://%s:%d/", host, port));
    }

    private String getResponse(URI uri) throws IOException
    {
        HttpURLConnection http = (HttpURLConnection)uri.toURL().openConnection();
        assertThat("Valid Response Code", http.getResponseCode(), anyOf(is(200), is(404)));

        try (InputStream in = http.getInputStream())
        {
            return IO.toString(in, StandardCharsets.UTF_8);
        }
    }

    @Test
    public void testReuseAddressDefault() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);
        server.setHandler(new ReuseInfoHandler());

        try
        {
            server.start();

            URI uri = toServerURI(connector);
            String response = getResponse(uri);
            assertThat("Response", response, containsString("connector.getReuseAddress() = true"));
            assertThat("Response", response, containsString("connector._reuseAddress() = true"));

            // Java on Windows is incapable of propagating reuse-address this to the opened socket.
            if (!org.junit.jupiter.api.condition.OS.WINDOWS.isCurrentOs())
            {
                assertThat("Response", response, containsString("socket.getReuseAddress() = true"));
            }
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testReuseAddressTrue() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        connector.setReuseAddress(true);
        server.addConnector(connector);
        server.setHandler(new ReuseInfoHandler());

        try
        {
            server.start();

            URI uri = toServerURI(connector);
            String response = getResponse(uri);
            assertThat("Response", response, containsString("connector.getReuseAddress() = true"));
            assertThat("Response", response, containsString("connector._reuseAddress() = true"));

            // Java on Windows is incapable of propagating reuse-address this to the opened socket.
            if (!org.junit.jupiter.api.condition.OS.WINDOWS.isCurrentOs())
            {
                assertThat("Response", response, containsString("socket.getReuseAddress() = true"));
            }
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testReuseAddressFalse() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        connector.setReuseAddress(false);
        server.addConnector(connector);

        server.setHandler(new ReuseInfoHandler());

        try
        {
            server.start();

            URI uri = toServerURI(connector);
            String response = getResponse(uri);
            assertThat("Response", response, containsString("connector.getReuseAddress() = false"));
            assertThat("Response", response, containsString("connector._reuseAddress() = false"));

            // Java on Windows is incapable of propagating reuse-address this to the opened socket.
            if (!org.junit.jupiter.api.condition.OS.WINDOWS.isCurrentOs())
            {
                assertThat("Response", response, containsString("socket.getReuseAddress() = false"));
            }
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "SO_REUSEPORT not available on windows")
    public void testReusePort() throws Exception
    {
        int port;
        try (ServerSocket server = new ServerSocket())
        {
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("localhost", 0));
            port = server.getLocalPort();
        }

        Server server = new Server();
        try
        {
            // Two connectors listening on the same port.
            ServerConnector connector1 = new ServerConnector(server, 1, 1);
            connector1.setReuseAddress(true);
            connector1.setReusePort(true);
            connector1.setPort(port);
            server.addConnector(connector1);
            ServerConnector connector2 = new ServerConnector(server, 1, 1);
            connector2.setReuseAddress(true);
            connector2.setReusePort(true);
            connector2.setPort(port);
            server.addConnector(connector2);

            server.setHandler(new Handler.Processor()
            {
                @Override
                public void process(Request request, Response response, Callback callback)
                {
                    callback.succeeded();
                }
            });

            server.start();

            try (SocketChannel client = SocketChannel.open(new InetSocketAddress("localhost", port)))
            {
                HttpTester.Request request = HttpTester.newRequest();
                request.put(HttpHeader.HOST, "localhost");
                client.write(request.generate());
                HttpTester.Response response = HttpTester.parseResponse(HttpTester.from(client));
                assertNotNull(response);
                assertEquals(HttpStatus.OK_200, response.getStatus());
            }
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testAddFirstConnectionFactory()
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        HttpConnectionFactory http = new HttpConnectionFactory();
        connector.addConnectionFactory(http);
        ProxyConnectionFactory proxy = new ProxyConnectionFactory();
        connector.addFirstConnectionFactory(proxy);

        Collection<ConnectionFactory> factories = connector.getConnectionFactories();
        assertEquals(2, factories.size());
        assertSame(proxy, factories.iterator().next());
        assertEquals(2, connector.getBeans(ConnectionFactory.class).size());
        assertEquals(proxy.getProtocol(), connector.getDefaultProtocol());
    }

    @Test
    public void testExceptionWhileAccepting() throws Exception
    {
        Server server = new Server();
        try (StacklessLogging ignored = new StacklessLogging(AbstractConnector.class))
        {
            AtomicLong spins = new AtomicLong();
            ServerConnector connector = new ServerConnector(server, 1, 1)
            {
                @Override
                public void accept(int acceptorID) throws IOException
                {
                    spins.incrementAndGet();
                    throw new IOException("explicitly_thrown_by_test");
                }
            };
            server.addConnector(connector);
            server.start();

            Thread.sleep(1500);
            assertThat(spins.get(), Matchers.lessThan(5L));
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testOpenWithServerSocketChannel() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        ServerSocketChannel channel = ServerSocketChannel.open();
        channel.bind(new InetSocketAddress(0));

        assertTrue(channel.isOpen());
        int port = channel.socket().getLocalPort();
        assertThat(port, greaterThan(0));

        connector.open(channel);

        assertThat(connector.getLocalPort(), is(port));

        server.start();

        assertThat(connector.getLocalPort(), is(port));
        assertThat(connector.getTransport(), is(channel));

        server.stop();

        assertThat(connector.getTransport(), Matchers.nullValue());
    }

    @Test
    public void testBindToAddressWhichIsInUse() throws Exception
    {
        try (ServerSocket socket = new ServerSocket(0))
        {
            final int port = socket.getLocalPort();

            Server server = new Server();
            ServerConnector connector = new ServerConnector(server);
            connector.setPort(port);
            server.addConnector(connector);

            server.setHandler(new DefaultHandler());

            IOException x = assertThrows(IOException.class, server::start);
            assertThat(x.getCause(), instanceOf(BindException.class));
            assertThat(x.getMessage(), containsString("0.0.0.0:" + port));
        }
    }
}
