//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.server;

import static org.hamcrest.Matchers.*;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.helper.CaptureSocket;
import org.eclipse.jetty.websocket.server.helper.SessionServlet;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class WebSocketOverSSLTest
{
    private static SimpleServletServer server;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new SessionServlet());
        server.enableSsl(true);
        server.start();
    }

    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }

    /**
     * Test the requirement of issuing socket and receiving echo response
     */
    @Test
    public void testEcho() throws Exception
    {
        Assert.assertThat("server scheme",server.getServerUri().getScheme(),is("wss"));
        WebSocketClient client = new WebSocketClient(server.getSslContextFactory());
        try
        {
            client.start();

            CaptureSocket clientSocket = new CaptureSocket();
            Future<Session> fut = client.connect(clientSocket,server.getServerUri());

            // wait for connect
            Session session = fut.get(1,TimeUnit.SECONDS);

            // Ask server socket

            // Generate text frame
            String msg = "this is an echo ... cho ... ho ... o";
            session.getRemote().sendString(msg);

            // Read frame (hopefully text frame)
            clientSocket.messages.awaitEventCount(1,500,TimeUnit.MILLISECONDS);
            EventQueue<String> captured = clientSocket.messages;
            Assert.assertThat("Text Message",captured.poll(),is(msg));

            // Shutdown the socket
            clientSocket.close();
        }
        finally
        {
            client.stop();
        }
    }

    /**
     * Test that server session reports as secure
     */
    @Test
    public void testServerSessionIsSecure() throws Exception
    {
        Assert.assertThat("server scheme",server.getServerUri().getScheme(),is("wss"));
        WebSocketClient client = new WebSocketClient(server.getSslContextFactory());
        try
        {
            client.start();

            CaptureSocket clientSocket = new CaptureSocket();
            Future<Session> fut = client.connect(clientSocket,server.getServerUri());

            // wait for connect
            Session session = fut.get(1,TimeUnit.SECONDS);

            // Ask server socket

            // Generate text frame
            session.getRemote().sendString("session.isSecure");

            // Read frame (hopefully text frame)
            clientSocket.messages.awaitEventCount(1,500,TimeUnit.MILLISECONDS);
            EventQueue<String> captured = clientSocket.messages;
            Assert.assertThat("Server.session.isSecure",captured.poll(),is("session.isSecure=true"));

            // Shutdown the socket
            clientSocket.close();
        }
        finally
        {
            client.stop();
        }
    }
}
