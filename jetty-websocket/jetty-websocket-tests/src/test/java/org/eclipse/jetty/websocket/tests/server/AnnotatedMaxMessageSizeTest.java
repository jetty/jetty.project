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

package org.eclipse.jetty.websocket.tests.server;

import static org.hamcrest.Matchers.anything;
import static org.hamcrest.Matchers.is;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.LocalServer;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class AnnotatedMaxMessageSizeTest
{
    @WebSocket(maxTextMessageSize = 64 * 1024, maxBinaryMessageSize = 64 * 1024)
    public static class BigEchoSocket
    {
        private static final Logger LOG = Log.getLogger(BigEchoSocket.class);
        
        @OnWebSocketMessage
        public void onBinary(Session session, byte buf[], int offset, int length) throws IOException
        {
            if (!session.isOpen())
            {
                LOG.warn("Session is closed");
                return;
            }
            RemoteEndpoint remote = session.getRemote();
            remote.sendBinary(ByteBuffer.wrap(buf, offset, length));
        }
        
        @OnWebSocketMessage
        public void onText(Session session, String message) throws IOException
        {
            if (!session.isOpen())
            {
                LOG.warn("Session is closed");
                return;
            }
            RemoteEndpoint remote = session.getRemote();
            remote.sendText(message);
        }
    }
    
    private static LocalServer server;
    private static URI serverUri;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new LocalServer();
        server.registerWebSocket("/", (req,resp) -> new BigEchoSocket());
        server.start();
        
        serverUri = server.getWsUri();
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
    
    @Test
    public void testEchoGood() throws Exception
    {
        URI wsUri = serverUri.resolve("/");
        
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setSubProtocols("echo");
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
        
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        
        // Message
        String msg = "this is an echo ... cho ... ho ... o";
        clientSession.getRemote().sendText(msg);
        
        // Read message
        String incomingMsg = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        Assert.assertThat("Incoming Message", incomingMsg, is(msg));
        
        clientSession.close();
    }
    
    @Test(timeout = 60000)
    public void testEchoTooBig() throws Exception
    {
        URI wsUri = serverUri.resolve("/");
    
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.setSubProtocols("echo");
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
    
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        
        // Big Message
        int size = 120 * 1024;
        byte buf[] = new byte[size]; // buffer bigger than maxMessageSize
        Arrays.fill(buf, (byte) 'x');
        String msg = new String(buf, StandardCharsets.UTF_8);
        clientSession.getRemote().sendText(msg);
    
        // Read message
        clientSocket.awaitCloseEvent("Client");
        clientSocket.assertCloseStatus("Client", StatusCode.MESSAGE_TOO_LARGE, anything());
    }
}
