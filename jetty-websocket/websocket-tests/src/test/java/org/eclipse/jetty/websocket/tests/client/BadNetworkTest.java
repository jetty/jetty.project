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

package org.eclipse.jetty.websocket.tests.client;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.LeakTrackingBufferPoolRule;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.eclipse.jetty.websocket.tests.UntrustedWSServer;
import org.eclipse.jetty.websocket.tests.UntrustedWSSession;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/**
 * Tests for conditions due to bad networking.
 */
public class BadNetworkTest
{
    @Rule
    public TestName testname = new TestName();
    
    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule("Test");
    
    private UntrustedWSServer server;
    private WebSocketClient client;
    
    @Before
    public void startClient() throws Exception
    {
        client = new WebSocketClient(bufferPool);
        client.getPolicy().setIdleTimeout(250);
        client.start();
    }
    
    @Before
    public void startServer() throws Exception
    {
        server = new UntrustedWSServer();
        server.start();
    }
    
    @After
    public void stopClient() throws Exception
    {
        client.stop();
    }
    
    @After
    public void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Test
    public void testAbruptClientClose() throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
    
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
        
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);
        
        // Validate that we are connected
        assertThat("Client Open Event Received", clientSocket.openLatch.await(30, TimeUnit.SECONDS), is(true));
        
        // Have client disconnect abruptly
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        clientSession.disconnect();
        
        // Client Socket should see close
        clientSocket.awaitCloseEvent("Client");
        
        // Client Socket should see a close event, with status NO_CLOSE
        // This event is automatically supplied by the underlying WebSocketClientConnection
        // in the situation of a bad network connection.
        clientSocket.assertCloseInfo("Client", StatusCode.NO_CLOSE, containsString("disconnect"));
    }
    
    @Test
    public void testAbruptServerClose() throws Exception
    {
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
    
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
    
        CompletableFuture<UntrustedWSSession> sessionFuture = new CompletableFuture<UntrustedWSSession>()
        {
            @Override
            public boolean complete(UntrustedWSSession session)
            {
                // server disconnect
                session.disconnect();
                return super.complete(session);
            }
        };
        server.registerOnOpenFuture(wsUri, sessionFuture);
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri);
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        
        // Validate that we are connected
        clientSocket.awaitOpenEvent("Client");
        
        // Wait for close (as response to idle timeout)
        clientSocket.awaitCloseEvent("Client");
        
        // Client Socket should see a close event, with status NO_CLOSE
        // This event is automatically supplied by the underlying WebSocketClientConnection
        // in the situation of a bad network connection.
        clientSocket.assertCloseInfo("Client", StatusCode.PROTOCOL, containsString("EOF"));
    }
}
