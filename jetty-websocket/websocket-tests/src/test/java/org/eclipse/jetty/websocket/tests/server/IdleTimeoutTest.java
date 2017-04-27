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

package org.eclipse.jetty.websocket.tests.server;

import static org.hamcrest.Matchers.containsString;

import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.SimpleServletServer;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class IdleTimeoutTest
{
    @WebSocket(maxIdleTime = 500)
    public static class FastTimeoutRFCSocket extends RFC6455Socket
    {
    }
    
    @SuppressWarnings("serial")
    public static class TimeoutServlet extends WebSocketServlet
    {
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.register(FastTimeoutRFCSocket.class);
        }
    }
    
    private static SimpleServletServer server;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new TimeoutServlet());
        server.start();
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Rule
    public TestName testname = new TestName();
    
    private WebSocketClient client;
    
    @Before
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.start();
    }
    
    @After
    public void stopClient() throws Exception
    {
        client.stop();
    }
    
    /**
     * Test IdleTimeout on server.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testIdleTimeout() throws Exception
    {
        client.setMaxIdleTimeout(2500);
        URI wsUri = server.getServerUri();
        
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setSubProtocols("onConnect");
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
        
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        
        // This wait should be shorter than client timeout above, but
        // longer than server timeout configured in FastTimeoutRFCSocket
        // eg: websocket server endpoint timeout < this timeout < websocket client idle timeout
        TimeUnit.MILLISECONDS.sleep(1000);
        
        // Write to server
        // This action is possible, but does nothing.
        // Server could be in a half-closed state at this point.
        // Where the server read is closed (due to timeout), but the server write is still open.
        // The server could not read this frame, if it is in this half closed state
        clientSession.getRemote().sendString("Hello");
        
        // Expect closure, as server should have timed out
        clientSocket.awaitCloseEvent("Client");
        clientSocket.assertCloseInfo("Client", StatusCode.SHUTDOWN, containsString("Timeout"));
        
        clientSession.close();
    }
}
