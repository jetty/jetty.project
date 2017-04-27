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

package org.eclipse.jetty.websocket.jsr356.server;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.test.LeakTrackingBufferPoolRule;
import org.eclipse.jetty.websocket.jsr356.server.samples.echo.EchoReturnEndpoint;
import org.junit.Rule;
import org.junit.Test;

public class OnMessageReturnTest
{
    @Rule
    public TestingDir testdir = new TestingDir();

    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule("Test");

    @Test
    public void testEchoReturn() throws Exception
    {
        WSServer wsb = new WSServer(testdir,"app");
        wsb.copyWebInf("empty-web.xml");
        wsb.copyClass(EchoReturnEndpoint.class);

        try
        {
            wsb.start();
            URI uri = wsb.getServerBaseURI();

            WebAppContext webapp = wsb.createWebAppContext();
            wsb.deployWebapp(webapp);

            WebSocketClient client = new WebSocketClient(bufferPool);
            try
            {
                client.start();
                
                JettyEchoSocket clientSocket = new JettyEchoSocket();
                Future<Session> clientConnectFuture = client.connect(clientSocket,uri.resolve("echoreturn"));
                
                // wait for connect
                Session clientSession = clientConnectFuture.get(5,TimeUnit.SECONDS);
                
                // Send message
                clientSocket.sendMessage("Hello World");
                
                // Confirm response
                String incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
                assertThat("Expected message",incomingMessage,is("Hello World"));
    
                clientSession.close();
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            wsb.stop();
        }
    }

}
