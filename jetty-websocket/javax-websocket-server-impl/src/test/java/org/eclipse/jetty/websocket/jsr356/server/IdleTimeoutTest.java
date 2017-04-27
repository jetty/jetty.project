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
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.test.LeakTrackingBufferPoolRule;
import org.eclipse.jetty.websocket.jsr356.server.samples.idletimeout.IdleTimeoutContextListener;
import org.eclipse.jetty.websocket.jsr356.server.samples.idletimeout.OnOpenIdleTimeoutEndpoint;
import org.eclipse.jetty.websocket.jsr356.server.samples.idletimeout.OnOpenIdleTimeoutSocket;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;

public class IdleTimeoutTest
{
    private static final Logger LOG = Log.getLogger(IdleTimeoutTest.class);

    @Rule
    public TestingDir testdir = new TestingDir();

    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule("Test");

    private static WSServer server;

    @BeforeClass
    public static void setupServer() throws Exception
    {
        server = new WSServer(MavenTestingUtils.getTargetTestingPath(IdleTimeoutTest.class.getName()),"app");
        server.copyWebInf("idle-timeout-config-web.xml");
        // the endpoint (extends javax.websocket.Endpoint)
        server.copyClass(OnOpenIdleTimeoutEndpoint.class);
        // the configuration that adds the endpoint
        server.copyClass(IdleTimeoutContextListener.class);
        // the annotated socket
        server.copyClass(OnOpenIdleTimeoutSocket.class);

        server.start();

        WebAppContext webapp = server.createWebAppContext();
        server.deployWebapp(webapp);
        // wsb.dump();
    }

    @AfterClass
    public static void stopServer()
    {
        server.stop();
    }

    private void assertConnectionTimeout(URI uri) throws Exception
    {
        WebSocketClient client = new WebSocketClient(bufferPool);
        try
        {
            client.start();
            JettyEchoSocket clientSocket = new JettyEchoSocket();
            
            Future<Session> clientConnectFuture = client.connect(clientSocket,uri);
            // wait for connect
            clientConnectFuture.get(1,TimeUnit.SECONDS);
            // wait 1 second
            TimeUnit.SECONDS.sleep(1);
            
            // Try to write
            clientSocket.sendMessage("You shouldn't be there");
            
            // See if remote sent anything (it shouldn't have)
            String incomingMessage = clientSocket.messageQueue.poll(1, TimeUnit.SECONDS);
            assertThat("Should not have received messages echoed back",incomingMessage,nullValue());
            
            // wait for local close
            clientSocket.awaitCloseEvent("Client");
            clientSocket.assertCloseInfo("Client", StatusCode.SHUTDOWN, containsString("Idle Timeout"));
        }
        finally
        {
            client.stop();
        }
    }

    @Test
    public void testAnnotated() throws Exception
    {
        URI uri = server.getServerBaseURI();
        assertConnectionTimeout(uri.resolve("idle-onopen-socket"));
    }

    @Test
    public void testEndpoint() throws Exception
    {
        URI uri = server.getServerBaseURI();
        assertConnectionTimeout(uri.resolve("idle-onopen-endpoint"));
    }
}
