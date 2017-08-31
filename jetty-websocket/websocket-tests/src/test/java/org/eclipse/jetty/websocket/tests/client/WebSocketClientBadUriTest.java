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

import static org.hamcrest.Matchers.greaterThanOrEqualTo;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jetty.toolchain.test.TestTracker;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.impl.WebSocketClientImpl;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class WebSocketClientBadUriTest
{
    @WebSocket
    public static class OpenTrackingSocket
    {
        public CountDownLatch openLatch = new CountDownLatch(1);
        
        @OnWebSocketConnect
        public void onOpen(Session session)
        {
            openLatch.countDown();
        }
        
        public void assertNotOpened()
        {
            Assert.assertThat("Open Latch", openLatch.getCount(), greaterThanOrEqualTo(1L));
        }
    }
    
    @Parameters
    public static Collection<String[]> data()
    {
        List<String[]> data = new ArrayList<>();
        // @formatter:off
        // - not using right scheme
        data.add(new String[]{"http://localhost"});
        data.add(new String[]{"https://localhost"});
        data.add(new String[]{"file://localhost"});
        data.add(new String[]{"content://localhost"});
        data.add(new String[]{"jar://localhost"});
        // - non-absolute uri
        data.add(new String[]{"/mysocket"});
        data.add(new String[]{"/sockets/echo"});
        data.add(new String[]{"#echo"});
        data.add(new String[]{"localhost:8080/echo"});
        // @formatter:on
        return data;
    }
    
    @Rule
    public TestTracker tt = new TestTracker();
    
    private WebSocketClientImpl client;
    private final String uriStr;
    private final URI uri;
    
    public WebSocketClientBadUriTest(String rawUri)
    {
        this.uriStr = rawUri;
        this.uri = URI.create(uriStr);
    }
    
    @Before
    public void startClient() throws Exception
    {
        client = new WebSocketClientImpl();
        client.start();
    }
    
    @After
    public void stopClient() throws Exception
    {
        client.stop();
    }
    
    @Test
    public void testBadURI() throws Exception
    {
        OpenTrackingSocket clientSocket = new OpenTrackingSocket();
        
        try
        {
            client.connect(clientSocket, uri); // should toss exception
            
            Assert.fail("Expected IllegalArgumentException");
        }
        catch (IllegalArgumentException e)
        {
            // expected path
            clientSocket.assertNotOpened();
        }
    }
}
