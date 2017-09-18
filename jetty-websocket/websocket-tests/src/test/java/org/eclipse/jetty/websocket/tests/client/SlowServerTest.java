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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.client.impl.WebSocketClient;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.eclipse.jetty.websocket.tests.UntrustedWSServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

@Ignore("Not working yet")
public class SlowServerTest
{
    @Rule
    public TestName testname = new TestName();
    
    private UntrustedWSServer server;
    private WebSocketClient client;
    
    @Before
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.setMaxIdleTimeout(60000);
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
    public void testServerSlowToRead() throws Exception
    {
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testname.getMethodName());
        // client.setMasker(new ZeroMasker());
        client.setMaxIdleTimeout(60000);
    
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
        
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setSubProtocols("echo");
        Future<Session> clientConnectFuture = client.connect(clientEndpoint, wsUri);
    
        // Confirm connected
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat("Client open event", clientEndpoint.openLatch.await(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS), is(true));
        
        int messageCount = 10;
        
        // Setup slow server read
        // TODO: setup a slow EndPoint READ on the server
        
        // Have client write as quickly as it can.
        ClientWriteThread writer = new ClientWriteThread(clientSession);
        writer.setMessageCount(messageCount);
        writer.setMessage("Hello");
        writer.setSlowness(-1); // disable slowness
        writer.start();
        writer.join();
    
        // Verify receive
        for (int i = 0; i < messageCount; i++)
        {
            String expectedMsg = "Hello";
            String incomingMessage = clientEndpoint.messageQueue.poll(1, TimeUnit.SECONDS);
            assertThat("Received Message[" + (i + 1) + "/" + messageCount + "]", incomingMessage, is(expectedMsg));
        }
    
        // Wait for completion
        writer.join();
    
        // Close
        clientSession.close();
        assertTrue("Client close event", clientEndpoint.closeLatch.await(Defaults.CLOSE_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        clientEndpoint.assertCloseInfo("Client", StatusCode.NORMAL, is("Done"));
    }
    
    @Test
    public void testServerSlowToSend() throws Exception
    {
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testname.getMethodName());
        // client.setMasker(new ZeroMasker());
        client.setMaxIdleTimeout(60000);
    
        URI wsUri = server.getUntrustedWsUri(this.getClass(), testname);
    
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setSubProtocols("echo");
        Future<Session> clientConnectFuture = client.connect(clientEndpoint, wsUri);
    
        // Confirm connected
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat("Client open event", clientEndpoint.openLatch.await(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS), is(true));
    
        int messageCount = 1000;
        
        // Setup slow server read
        // TODO: setup a slow EndPoint READ on the server
        
        // Have server write slowly.
//        ServerWriteThread writer = new ServerWriteThread(serverConn);
//        writer.setMessageCount(messageCount);
//        writer.setMessage("Hello");
//        writer.setSlowness(10);
//        writer.start();
//        writer.join();
    
        // Verify receive
        for (int i = 0; i < messageCount; i++)
        {
            String expectedMsg = "Hello";
            String incomingMessage = clientEndpoint.messageQueue.poll(1, TimeUnit.SECONDS);
            assertThat("Received Message[" + (i + 1) + "/" + messageCount + "]", incomingMessage, is(expectedMsg));
        }
    
        // Wait for completion
    
        // Close
        clientSession.close();
        assertTrue("Client close event", clientEndpoint.closeLatch.await(Defaults.CLOSE_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        clientEndpoint.assertCloseInfo("Client", StatusCode.NORMAL, is("Done"));

    }
}
