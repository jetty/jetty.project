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

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.io.ChannelEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ManagedSelector;
import org.eclipse.jetty.io.SocketChannelEndPoint;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Extended Server Tester.
 */
public class ExtendedServerTest extends HttpServerTestBase
{
    @Before
    public void init() throws Exception
    {
        startServer(new ServerConnector(_server,new HttpConnectionFactory()
        {
            @Override
            public Connection newConnection(Connector connector, EndPoint endPoint)
            {
                return configure(new ExtendedHttpConnection(getHttpConfiguration(), connector, endPoint), connector, endPoint);
            }
        })
        {
            @Override
            protected ChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key) throws IOException
            {
                return new ExtendedEndPoint(channel,selectSet,key, getScheduler());
            }

        });
    }

    private static class ExtendedEndPoint extends SocketChannelEndPoint
    {
        private volatile long _lastSelected;

        public ExtendedEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey key, Scheduler scheduler)
        {
            super(channel,selector,key,scheduler);
        }

        public ExtendedEndPoint(SocketChannel channel, ManagedSelector selector, SelectionKey key, Scheduler scheduler)
        {
            super(channel,selector,key,scheduler);
        }

        @Override
        public Runnable onSelected()
        {
            _lastSelected=System.currentTimeMillis();
            return super.onSelected();
        }

        long getLastSelected()
        {
            return _lastSelected;
        }
    }

    private static class ExtendedHttpConnection extends HttpConnection
    {
        public ExtendedHttpConnection(HttpConfiguration config, Connector connector, EndPoint endPoint)
        {
            super(config,connector,endPoint,HttpCompliance.RFC7230,false);
        }

        @Override
        protected HttpChannelOverHttp newHttpChannel()
        {
            return new HttpChannelOverHttp(this, getConnector(), getHttpConfiguration(), getEndPoint(), this)
            {
                @Override
                public boolean startRequest(String method, String uri, HttpVersion version)
                {
                    getRequest().setAttribute("DispatchedAt",((ExtendedEndPoint)getEndPoint()).getLastSelected());
                    return super.startRequest(method,uri,version);
                }
            };
        }
    }

    @Test
    public void testExtended() throws Exception
    {
        configureServer(new DispatchedAtHandler());

        try (Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort()))
        {
            OutputStream os = client.getOutputStream();

            long start=System.currentTimeMillis();
            os.write("GET / HTTP/1.0\r\n".getBytes(StandardCharsets.ISO_8859_1));
            os.flush();
            Thread.sleep(200);
            long end=System.currentTimeMillis();
            os.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
            
            // Read the response.
            String response = readResponse(client);

            Assert.assertThat(response, Matchers.containsString("HTTP/1.1 200 OK"));
            Assert.assertThat(response, Matchers.containsString("DispatchedAt="));
            
            String s=response.substring(response.indexOf("DispatchedAt=")+13);
            s=s.substring(0,s.indexOf('\n'));
            long dispatched=Long.valueOf(s);
            
            Assert.assertThat(dispatched, Matchers.greaterThanOrEqualTo(start));
            Assert.assertThat(dispatched, Matchers.lessThan(end));
        }
    }
    

    protected static class DispatchedAtHandler extends AbstractHandler
    {
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            response.setStatus(200);
            response.getOutputStream().print("DispatchedAt="+request.getAttribute("DispatchedAt")+"\r\n");
        }
    }
}
