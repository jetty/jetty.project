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

package org.eclipse.jetty.websocket.jsr356;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javax.websocket.ClientEndpoint;
import javax.websocket.OnMessage;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.core.WebSocketLocalEndpoint;
import org.eclipse.jetty.websocket.core.frames.BinaryFrame;
import org.eclipse.jetty.websocket.jsr356.sockets.TrackingSocket;
import org.junit.Test;

public class JavaxWebSocketLocalEndpoint_OnMessage_BinaryStreamTest extends AbstractJavaxWebSocketLocalEndpointTest
{
    @SuppressWarnings("Duplicates")
    private TrackingSocket performOnMessageInvocation(TrackingSocket socket, Function<WebSocketLocalEndpoint, Void> func) throws Exception
    {
        JavaxWebSocketLocalEndpointFactory factory = new JavaxWebSocketLocalEndpointFactory();
        BasicEndpointConfig config = new BasicEndpointConfig();
        ConfiguredEndpoint endpoint = new ConfiguredEndpoint(socket, config);
        JavaxWebSocketSession session = newSession();

        JavaxWebSocketLocalEndpoint localEndpoint = factory.createLocalEndpoint(endpoint,
                session, container.getPolicy(), container.getExecutor());

        // This invocation is the same for all tests
        localEndpoint.onOpen();
        
        func.apply(localEndpoint);
        
        return socket;
    }

    @ClientEndpoint
    public static class MessageStreamSocket extends TrackingSocket
    {
        @OnMessage
        public void onMessage(InputStream stream)
        {
            try
            {
                String msg = IO.toString(stream, StandardCharsets.UTF_8);
                addEvent("onMessage(%s) = \"%s\"", stream.getClass().getSimpleName(), msg);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
    }
    
    @Test
    public void testInvokeMessageStream() throws Exception
    {
        TrackingSocket socket = performOnMessageInvocation(new MessageStreamSocket(), (endpoint) ->
        {
            endpoint.onBinary(new BinaryFrame().setPayload("Hello World").setFin(true), Callback.NOOP);
            return null;
        });
        
        String event = socket.events.poll(1, TimeUnit.SECONDS);
        assertThat("event", event, is("onMessage(MessageInputStream) = \"Hello World\""));
    }
}
