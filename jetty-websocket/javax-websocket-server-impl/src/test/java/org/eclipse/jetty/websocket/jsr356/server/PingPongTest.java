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

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.websocket.ContainerProvider;
import javax.websocket.WebSocketContainer;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.jsr356.server.samples.pong.PongContextListener;
import org.eclipse.jetty.websocket.jsr356.server.samples.pong.PongMessageEndpoint;
import org.eclipse.jetty.websocket.jsr356.server.samples.pong.PongSocket;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class PingPongTest
{
    private static WSServer server;
    private static URI serverUri;
    private static WebSocketContainer client;

    @BeforeClass
    public static void startServer() throws Exception
    {
        Path testdir = MavenTestingUtils.getTargetTestingPath(PingPongTest.class.getName());
        server = new WSServer(testdir,"app");
        server.copyWebInf("pong-config-web.xml");

        server.copyClass(PongContextListener.class);
        server.copyClass(PongMessageEndpoint.class);
        server.copyClass(PongSocket.class);

        server.start();
        serverUri = server.getServerBaseURI();

        WebAppContext webapp = server.createWebAppContext();
        server.deployWebapp(webapp);
    }

    @BeforeClass
    public static void startClient() throws Exception
    {
        client = ContainerProvider.getWebSocketContainer();
    }

    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }
    
    private void assertEcho(String endpointPath, Function<EchoClientSocket,Void> sendAction, String ... expectedMsgs) throws Exception
    {
        EchoClientSocket socket = new EchoClientSocket();
        URI toUri = serverUri.resolve(endpointPath);
    
        try
        {
            Future<List<String>> clientMessagesFuture = socket.expectedMessages(expectedMsgs.length);
        
            // Connect
            client.connectToServer(socket,toUri);
            socket.openLatch.await(2, TimeUnit.SECONDS);
        
            // Apply send action
            sendAction.apply(socket);
        
            // Collect Responses
            List<String> msgs = clientMessagesFuture.get(5, TimeUnit.SECONDS);
    
            // Validate Responses
            for(int i=0; i<expectedMsgs.length; i++)
            {
                assertThat("Expected message[" + i + "]",msgs.get(i),containsString(expectedMsgs[i]));
            }
        }
        finally
        {
            // Close
            socket.close();
        }
    }

    @Test(timeout = 6000)
    public void testPongEndpoint() throws Exception
    {
        assertEcho("pong", (socket) -> {
            try
            {
                socket.sendPong("hello");
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
            return null;
        }, "PongMessageEndpoint.onMessage(PongMessage):[/pong]:hello");
    }
    
    @Test(timeout = 6000)
    public void testPongSocket() throws Exception
    {
        assertEcho("pong-socket", (socket) -> {
            try
            {
                socket.sendPong("hello");
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
            return null;
        }, "PongSocket.onPong(PongMessage)[/pong-socket]:hello");
    }
}
