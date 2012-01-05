/*******************************************************************************
 * Copyright (c) 2011 Intalio, Inc.
 * ======================================================================
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *   The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 *
 *   The Apache License v2.0 is available at
 *   http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 *******************************************************************************/
package org.eclipse.jetty.websocket;

import java.net.URI;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.helper.CaptureSocket;
import org.eclipse.jetty.websocket.helper.MessageSender;
import org.eclipse.jetty.websocket.helper.WebSocketCaptureServlet;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * WebSocketCommTest - to test reported undelivered messages in bug <a
 * href="https://jira.codehaus.org/browse/JETTY-1463">JETTY-1463</a>
 */
public class WebSocketCommTest
{
    private Server server;
    private WebSocketCaptureServlet servlet;
    private URI serverUri;

    @Before
    public void startServer() throws Exception
    {
        // Configure Server
        server = new Server(0);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        server.setHandler(context);

        // Serve capture servlet
        servlet = new WebSocketCaptureServlet();
        context.addServlet(new ServletHolder(servlet),"/");

        // Start Server
        server.start();

        Connector conn = server.getConnectors()[0];
        String host = conn.getHost();
        if (host == null)
        {
            host = "localhost";
        }
        int port = conn.getLocalPort();
        serverUri = new URI(String.format("ws://%s:%d/",host,port));
        System.out.printf("Server URI: %s%n",serverUri);
    }

    @After
    public void stopServer()
    {
        try
        {
            server.stop();
        }
        catch (Exception e)
        {
            e.printStackTrace(System.err);
        }
    }

    @Test
    public void testSendTextMessages() throws Exception
    {
        WebSocketClientFactory clientFactory = new WebSocketClientFactory();
        clientFactory.start();

        WebSocketClient wsc = clientFactory.newWebSocketClient();
        MessageSender sender = new MessageSender();
        wsc.open(serverUri,sender);

        try
        {
            sender.awaitConnect();

            // Send 5 short messages
            for (int i = 0; i < 5; i++)
            {
                System.out.printf("Sending msg-%d%n",i);
                sender.sendMessage("msg-%d",i);
            }

            // Servlet should show only 1 connection.
            Assert.assertThat("Servlet.captureSockets.size",servlet.captures.size(),is(1));

            CaptureSocket socket = servlet.captures.get(0);
            Assert.assertThat("CaptureSocket",socket,notNullValue());
            Assert.assertThat("CaptureSocket.isConnected",socket.awaitConnected(1000),is(true));

            // Give servlet time to process messages
            TimeUnit.MILLISECONDS.sleep(500);

            // Should have captured 5 messages.
            Assert.assertThat("CaptureSocket.messages.size",socket.messages.size(),is(5));
        }
        finally
        {
            System.out.println("Closing client socket");
            sender.close();
        }
    }
}
