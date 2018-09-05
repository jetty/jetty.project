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

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
            assertThat("Open Latch", openLatch.getCount(), greaterThanOrEqualTo(1L));
        }
    }
    

    public static Stream<Arguments> data()
    {
        List<String[]> data = new ArrayList<>();

        // - not using right scheme
        data.add(new String[]{"http://localhost"});
        data.add(new String[]{"https://localhost"});
        data.add(new String[]{"file://localhost"});
        data.add(new String[]{"content://localhost"});
        data.add(new String[]{"jar://localhost"});
        // - non-absolute uri
        data.add(new String[] { "/mysocket" });
        data.add(new String[] { "/sockets/echo" });
        data.add(new String[] { "#echo" });
        data.add(new String[] { "localhost:8080/echo" });

        return data.stream().map(Arguments::of);
    }

    
    private WebSocketClient client;

    private final String uriStr;
    private final URI uri;
    
    public WebSocketClientBadUriTest(String rawUri)
    {
        this.uriStr = rawUri;
        this.uri = URI.create(uriStr);
    }


    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.start();
    }

    @AfterEach
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
            client.connect( clientSocket, uri ); // should toss exception

            fail( "Expected IllegalArgumentException" );
        }
        catch ( IllegalArgumentException e )
        {
            // expected path
            clientSocket.assertNotOpened();
        }
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testBadURI(String uriStr) throws Exception
    {
        OpenTrackingSocket clientSocket = new OpenTrackingSocket();
        URI uri = URI.create(uriStr);

        assertThrows(IllegalArgumentException.class, ()-> client.connect(clientSocket, uri));
        clientSocket.assertNotOpened();
    }
}
