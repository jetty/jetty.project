//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.server;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.hamcrest.Matchers;
import org.junit.Test;

public class ServerConnectorTest
{
    public static class ReuseInfoHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            response.setContentType("text/plain");

            EndPoint endPoint = baseRequest.getHttpChannel().getEndPoint();
            assertThat("Endpoint",endPoint,instanceOf(SocketChannelEndPoint.class));
            SocketChannelEndPoint channelEndPoint = (SocketChannelEndPoint)endPoint;
            Socket socket = channelEndPoint.getSocket();
            ServerConnector connector = (ServerConnector)baseRequest.getHttpChannel().getConnector();

            PrintWriter out = response.getWriter();
            out.printf("connector.getReuseAddress() = %b%n",connector.getReuseAddress());

            try
            {
                Field fld = connector.getClass().getDeclaredField("_reuseAddress");
                assertThat("Field[_reuseAddress]",fld,notNullValue());
                fld.setAccessible(true);
                Object val = fld.get(connector);
                out.printf("connector._reuseAddress() = %b%n",val);
            }
            catch (Throwable t)
            {
                t.printStackTrace(out);
            }

            out.printf("socket.getReuseAddress() = %b%n",socket.getReuseAddress());

            baseRequest.setHandled(true);
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
        return new URI(String.format("http://%s:%d/",host,port));
    }

    private String getResponse(URI uri) throws IOException
    {
        HttpURLConnection http = (HttpURLConnection)uri.toURL().openConnection();
        assertThat("Valid Response Code",http.getResponseCode(),anyOf(is(200),is(404)));

        try (InputStream in = http.getInputStream())
        {
            return IO.toString(in,StandardCharsets.UTF_8);
        }
    }

    @Test
    public void testReuseAddress_Default() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        HandlerList handlers = new HandlerList();
        handlers.addHandler(new ReuseInfoHandler());
        handlers.addHandler(new DefaultHandler());

        server.setHandler(handlers);

        try
        {
            server.start();

            URI uri = toServerURI(connector);
            String response = getResponse(uri);
            assertThat("Response",response,containsString("connector.getReuseAddress() = true"));
            assertThat("Response",response,containsString("connector._reuseAddress() = true"));

            // Java on Windows is incapable of propagating reuse-address this to the opened socket.
            if (!OS.IS_WINDOWS)
            {
                assertThat("Response",response,containsString("socket.getReuseAddress() = true"));
            }
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testReuseAddress_True() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        connector.setReuseAddress(true);
        server.addConnector(connector);

        HandlerList handlers = new HandlerList();
        handlers.addHandler(new ReuseInfoHandler());
        handlers.addHandler(new DefaultHandler());

        server.setHandler(handlers);

        try
        {
            server.start();

            URI uri = toServerURI(connector);
            String response = getResponse(uri);
            assertThat("Response",response,containsString("connector.getReuseAddress() = true"));
            assertThat("Response",response,containsString("connector._reuseAddress() = true"));

            // Java on Windows is incapable of propagating reuse-address this to the opened socket.
            if (!OS.IS_WINDOWS)
            {
                assertThat("Response",response,containsString("socket.getReuseAddress() = true"));
            }
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testReuseAddress_False() throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        connector.setReuseAddress(false);
        server.addConnector(connector);

        HandlerList handlers = new HandlerList();
        handlers.addHandler(new ReuseInfoHandler());
        handlers.addHandler(new DefaultHandler());

        server.setHandler(handlers);

        try
        {
            server.start();

            URI uri = toServerURI(connector);
            String response = getResponse(uri);
            assertThat("Response",response,containsString("connector.getReuseAddress() = false"));
            assertThat("Response",response,containsString("connector._reuseAddress() = false"));

            // Java on Windows is incapable of propagating reuse-address this to the opened socket.
            if (!OS.IS_WINDOWS)
            {
                assertThat("Response",response,containsString("socket.getReuseAddress() = false"));
            }
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testAddFirstConnectionFactory() throws Exception
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
        try (StacklessLogging stackless = new StacklessLogging(AbstractConnector.class))
        {
            AtomicLong spins = new AtomicLong();
            ServerConnector connector = new ServerConnector(server)
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

            Thread.sleep(1000);
            assertThat(spins.get(), Matchers.lessThan(5L));
        }
        finally
        {
            server.stop();
        }
    }
}
