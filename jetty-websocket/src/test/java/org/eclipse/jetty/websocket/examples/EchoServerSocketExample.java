//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.examples;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.io.Buffer;
import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocketServlet;

public class EchoServerSocketExample
{
    public static class EchoSocket implements WebSocket, WebSocket.OnTextMessage, WebSocket.OnBinaryMessage
    {
        private static final Logger LOG = Log.getLogger(EchoSocket.class);

        public List<Throwable> errors = new ArrayList<Throwable>();
        private Connection connection;

        @Override
        public void onClose(int closeCode, String message)
        {
            LOG.info("Closed {} : {}",closeCode,message);
        }

        @Override
        public void onMessage(byte[] data, int offset, int length)
        {
            // Wrap in Jetty Buffer (only for logging reasons)
            Buffer buf = new ByteArrayBuffer(data,offset,length);
            LOG.info("on Text : {}",buf.toDetailString());

            try
            {
                // echo back this BINARY message
                this.connection.sendMessage(data,offset,length);
            }
            catch (IOException e)
            {
                LOG.warn(e);
            }
        }

        @Override
        public void onMessage(String message)
        {
            LOG.info("on Text : {}",message);

            try
            {
                // echo back this TEXT message
                this.connection.sendMessage(message);
            }
            catch (IOException e)
            {
                LOG.warn(e);
            }
        }

        @Override
        public void onOpen(Connection connection)
        {
            this.connection = connection;
            LOG.info("Connection opened : {}",connection);
        }
    }

    @SuppressWarnings("serial")
    public static class EchoSocketServlet extends WebSocketServlet
    {
        @Override
        public WebSocket doWebSocketConnect(HttpServletRequest request, String protocol)
        {
            return new EchoSocket();
        }
    }

    private static final Logger LOG = Log.getLogger(EchoServerSocketExample.class);

    public static void main(String[] args)
    {
        Server server = new Server(9090);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);

        // Setup websocket echo socket, via servlet, on url pattern "/echo"
        context.addServlet(EchoSocketServlet.class,"/echo");

        try
        {
            // Start server
            server.start();

            Connector conn = server.getConnectors()[0];
            String host = conn.getHost();
            if (host == null)
            {
                host = "localhost";
            }
            int port = conn.getLocalPort();
            URI serverUri = new URI(String.format("ws://%s:%d/echo",host,port));

            LOG.info("WebSocket Echo Server started on {}",serverUri);
            server.join();
        }
        catch (Throwable t)
        {
            LOG.warn(t);
        }
    }
}
