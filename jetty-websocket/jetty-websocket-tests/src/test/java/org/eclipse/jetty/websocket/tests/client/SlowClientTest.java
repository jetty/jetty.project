//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.eclipse.jetty.websocket.tests.WSServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SlowClientTest
{
    private WSServer server;
    private WebSocketClient client;
    
    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.getPolicy().setIdleTimeout(60000);
        client.start();
    }
    
    @BeforeEach
    public void startServer() throws Exception
    {
        server = new WSServer(
                MavenTestingUtils.getTargetTestingPath(SlowClientTest.class.getSimpleName()), ""
        );
        server.start();
    }
    
    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }
    
    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Test
    @Disabled("Not working yet")
    public void testClientSlowToSend(TestInfo testInfo) throws Exception
    {
        TrackingEndpoint clientEndpoint = new TrackingEndpoint(testInfo.getTestMethod().toString());
        client.getPolicy().setIdleTimeout(60000);
        
        URI wsUri = server.getServerUri();
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setSubProtocols("echo");
        Future<Session> clientConnectFuture = client.connect(clientEndpoint, wsUri);
        
        // Confirm connected
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        assertThat("Client onOpen event", clientEndpoint.openLatch.await(Defaults.OPEN_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS), is(true));
        
        int messageCount = 10;
        
        // Have client write slowly.
        ClientWriteThread writer = new ClientWriteThread(clientSession);
        writer.setMessageCount(messageCount);
        writer.setMessage("Hello");
        writer.setSlowness(10);
        writer.start();
        
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
        assertTrue(clientEndpoint.closeLatch.await(Defaults.CLOSE_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS), "Client close event");
        clientEndpoint.assertCloseStatus("Client", StatusCode.NORMAL, is("Done"));
    }
}
