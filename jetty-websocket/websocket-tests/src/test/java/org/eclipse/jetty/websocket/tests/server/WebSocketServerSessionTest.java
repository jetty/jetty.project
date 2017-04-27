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

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.AdvancedRunner;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
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
import org.junit.runner.RunWith;

/**
 * Testing various aspects of the server side support for WebSocket {@link org.eclipse.jetty.websocket.api.Session}
 */
@RunWith(AdvancedRunner.class)
public class WebSocketServerSessionTest
{
    public static class SessionServlet extends WebSocketServlet
    {
        @Override
        public void configure(WebSocketServletFactory factory)
        {
            factory.register(SessionSocket.class);
        }
    }
    
    @WebSocket
    public static class SessionSocket
    {
        private static final Logger LOG = Log.getLogger(SessionSocket.class);
        private Session session;
        
        @OnWebSocketConnect
        public void onConnect(Session sess)
        {
            this.session = sess;
        }
        
        @OnWebSocketMessage
        public void onText(String message)
        {
            LOG.debug("onText({})",message);
            if (message == null)
            {
                return;
            }
            
            try
            {
                if (message.startsWith("getParameterMap"))
                {
                    Map<String, List<String>> parameterMap = session.getUpgradeRequest().getParameterMap();
                    
                    int idx = message.indexOf('|');
                    String key = message.substring(idx + 1);
                    List<String> values = parameterMap.get(key);
                    
                    if (values == null)
                    {
                        sendString("<null>");
                        return;
                    }
                    
                    StringBuilder valueStr = new StringBuilder();
                    valueStr.append('[');
                    boolean delim = false;
                    for (String value : values)
                    {
                        if (delim)
                        {
                            valueStr.append(", ");
                        }
                        valueStr.append(value);
                        delim = true;
                    }
                    valueStr.append(']');
                    LOG.debug("valueStr = {}", valueStr);
                    sendString(valueStr.toString());
                    return;
                }
                
                if ("session.isSecure".equals(message))
                {
                    String issecure = String.format("session.isSecure=%b",session.isSecure());
                    sendString(issecure);
                    return;
                }
                
                if ("session.upgradeRequest.requestURI".equals(message))
                {
                    String response = String.format("session.upgradeRequest.requestURI=%s",session.getUpgradeRequest().getRequestURI().toASCIIString());
                    sendString(response);
                    return;
                }
                
                if ("harsh-disconnect".equals(message))
                {
                    session.disconnect();
                    return;
                }
                
                // echo the message back.
                sendString(message);
            }
            catch (Throwable t)
            {
                LOG.warn(t);
            }
        }
        
        protected void sendString(String text) throws IOException
        {
            RemoteEndpoint remote = session.getRemote();
            remote.sendString(text, null);
            if (remote.getBatchMode() == BatchMode.ON)
                remote.flush();
        }
    }
    
    private static SimpleServletServer server;

    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new SessionServlet());
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

    @Test
    public void testDisconnect() throws Exception
    {
        URI wsUri = server.getServerUri().resolve("/test/disconnect");
    
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
    
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        clientSession.getRemote().sendString("harsh-disconnect");
        
        // TODO: or onError(EOF)
        clientSocket.awaitCloseEvent("Client");
    }

    @Test
    public void testUpgradeRequestResponse() throws Exception
    {
        URI wsUri = server.getServerUri().resolve("/test?snack=cashews&amount=handful&brand=off");
    
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
    
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        clientSession.getRemote().sendString("getParameterMap|snack");
        clientSession.getRemote().sendString("getParameterMap|amount");
        clientSession.getRemote().sendString("getParameterMap|brand");
        clientSession.getRemote().sendString("getParameterMap|cost");
        
        String incomingMessage;
        incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Parameter Map[snack]", incomingMessage, is("[cashews]"));
        incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Parameter Map[amount]", incomingMessage, is("[handful]"));
        incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Parameter Map[brand]", incomingMessage, is("[off]"));
        incomingMessage = clientSocket.messageQueue.poll(5, TimeUnit.SECONDS);
        assertThat("Parameter Map[cost]", incomingMessage, is("<null>"));
    }
}
