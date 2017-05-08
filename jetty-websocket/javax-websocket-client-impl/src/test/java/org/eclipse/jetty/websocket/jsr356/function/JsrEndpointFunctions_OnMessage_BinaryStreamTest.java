//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.function;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import javax.websocket.ClientEndpoint;
import javax.websocket.OnMessage;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.api.FrameCallback;
import org.eclipse.jetty.websocket.common.frames.BinaryFrame;
import org.eclipse.jetty.websocket.common.function.EndpointFunctions;
import org.eclipse.jetty.websocket.jsr356.endpoints.TrackingSocket;
import org.junit.Test;

public class JsrEndpointFunctions_OnMessage_BinaryStreamTest extends AbstractJsrEndpointFunctionsTest
{
    @SuppressWarnings("Duplicates")
    private TrackingSocket performOnMessageInvocation(TrackingSocket socket, Function<EndpointFunctions, Void> func) throws Exception
    {
        // Establish endpoint function
        JsrEndpointFunctions endpointFunctions = new JsrEndpointFunctions(
                socket, container.getPolicy(),
                container.getExecutor(),
                encoders,
                decoders,
                uriParams,
                endpointConfig
        );
        endpointFunctions.start();
        
        // This invocation is the same for all tests
        endpointFunctions.onOpen(newSession(socket));
        
        func.apply(endpointFunctions);
        
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
    public void testInvokeMessageText() throws Exception
    {
        TrackingSocket socket = performOnMessageInvocation(new MessageStreamSocket(), (endpoint) ->
        {
            endpoint.onBinary(new BinaryFrame().setPayload("Hello World").setFin(true), new FrameCallback.Adapter());
            return null;
        });
        socket.assertEvent("onMessage(MessageInputStream) = \"Hello World\"");
    }
    
}
