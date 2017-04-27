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
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.net.URI;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.tests.Defaults;
import org.eclipse.jetty.websocket.tests.SimpleServletServer;
import org.eclipse.jetty.websocket.tests.TrackingEndpoint;
import org.eclipse.jetty.websocket.tests.servlets.EchoServlet;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class FragmentExtensionTest
{
    private static SimpleServletServer server;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new EchoServlet());
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
    
    private String[] split(String str, int partSize)
    {
        int strLength = str.length();
        int count = (int) Math.ceil((double) str.length() / partSize);
        String ret[] = new String[count];
        int idx;
        for (int i = 0; i < count; i++)
        {
            idx = (i * partSize);
            ret[i] = str.substring(idx, Math.min(idx + partSize, strLength));
        }
        return ret;
    }
    
    @Test
    public void testFragmentExtension() throws Exception
    {
        Assume.assumeTrue("Server has fragment registered",
                server.getWebSocketServletFactory().getExtensionFactory().isAvailable("fragment"));
        
        int fragSize = 4;
        
        URI wsUri = server.getServerUri();
        
        TrackingEndpoint clientSocket = new TrackingEndpoint(testname.getMethodName());
        ClientUpgradeRequest upgradeRequest = new ClientUpgradeRequest();
        upgradeRequest.addExtensions("fragment;maxLength=" + fragSize);
        upgradeRequest.setSubProtocols("onConnect");
        Future<Session> clientConnectFuture = client.connect(clientSocket, wsUri, upgradeRequest);
        
        Session clientSession = clientConnectFuture.get(Defaults.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        List<ExtensionConfig> extensionConfigList = clientSession.getUpgradeResponse().getExtensions();
        assertThat("Client Upgrade Response.Extensions", extensionConfigList.size(), is(1));
        assertThat("Client Upgrade Response.Extensions[0]", extensionConfigList.get(0).toString(), containsString("fragment"));
        
        // Message
        String msg = "Sent as a long message that should be split";
        clientSession.getRemote().sendString(msg);
        
        // Read message
        String parts[] = split(msg, fragSize);
        for (int i = 0; i < parts.length; i++)
        {
            WebSocketFrame frame = clientSocket.framesQueue.poll();
            Assert.assertThat("text[" + i + "].payload", frame.getPayloadAsUTF8(), is(parts[i]));
        }
        
        clientSession.close();
    }
}
