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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.eclipse.jetty.websocket.tests.UntrustedWSEndpoint;
import org.eclipse.jetty.websocket.tests.UntrustedWSServer;
import org.eclipse.jetty.websocket.tests.UntrustedWSSession;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class EchoTest
{
    @Rule
    public TestName testname = new TestName();
    
    private UntrustedWSServer server;
    private WebSocketClient client;
    
    @Before
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
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
    public void testBasicEcho() throws IOException, InterruptedException, ExecutionException, TimeoutException
    {
        // Set client timeout
        final int timeout = 1000;
        client.setMaxIdleTimeout(timeout);
    
        URI wsURI = server.getWsUri().resolve("/untrusted/" + testname.getMethodName());
        CompletableFuture<UntrustedWSSession> serverSessionFut = new CompletableFuture<UntrustedWSSession>()
        {
            @Override
            public boolean complete(UntrustedWSSession session)
            {
                // echo back text as-well
                session.getUntrustedEndpoint().setOnTextFunction((serverSession, text) -> text);
                return super.complete(session);
            }
        };
        server.registerConnectFuture(wsURI, serverSessionFut);
    
        // Client connects
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(WebSocketBehavior.CLIENT.name());
        Future<Session> clientConnectFuture = client.connect(clientEndpoint, wsURI);
    
        // Server accepts connect
        UntrustedWSSession serverSession = serverSessionFut.get(10, TimeUnit.SECONDS);
        UntrustedWSEndpoint serverEndpoint = serverSession.getUntrustedEndpoint();
    
        // client confirms connection via echo
        assertThat("Client onOpen event", clientEndpoint.openLatch.await(5, TimeUnit.SECONDS), is(true));
    
        // client sends message
        clientEndpoint.getRemote().sendString("Hello Echo");
        
        // Wait for response to echo
        String message = clientEndpoint.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("message", message, is("Hello Echo"));
        
        // client closes
        clientEndpoint.close(StatusCode.NORMAL, "Normal Close");
    
        // Server close event
        assertThat("Server onClose event", serverSession.getUntrustedEndpoint().closeLatch.await(5, TimeUnit.SECONDS), is(true));
        serverEndpoint.assertCloseInfo("Server", StatusCode.NORMAL, containsString("Normal Close"));

        // client triggers close event on client ws-endpoint
        assertThat("Client onClose event", clientEndpoint.closeLatch.await(5, TimeUnit.SECONDS), is(true));
        clientEndpoint.assertCloseInfo("Client", StatusCode.NORMAL, containsString("Normal Close"));
    }
    
}
