//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests.client.jsr356;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.util.concurrent.TimeUnit;

import javax.websocket.ContainerProvider;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.websocket.tests.SimpleServletServer;
import org.eclipse.jetty.websocket.tests.servlets.EchoServlet;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class AnnotatedEchoTest
{
    private static SimpleServletServer server;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new EchoServlet());
        server.start();
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Test
    public void testEcho() throws Exception
    {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        server.addBean(container); // allow to shutdown with server        Session session = null;
        Session session = null;
        try
        {
            AnnotatedEchoClient clientSocket = new AnnotatedEchoClient();
            session = container.connectToServer(clientSocket, server.getServerUri());
            session.getBasicRemote().sendText("Echo");
            String msg = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("message", msg, is("Echo"));
        }
        finally
        {
            session.close();
        }
    }
}
