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

package org.eclipse.jetty.websocket.jsr356.server.functions;

import java.io.IOException;
import java.io.Reader;
import java.util.function.Function;

import javax.websocket.OnMessage;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.api.FrameCallback;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.function.EndpointFunctions;
import org.eclipse.jetty.websocket.jsr356.server.JsrServerEndpointFunctions;
import org.eclipse.jetty.websocket.jsr356.server.TrackingSocket;
import org.junit.Test;

public class JsrServerEndpointFunctions_OnMessage_TextStreamTest extends AbstractJsrEndpointFunctionsTest
{
    @SuppressWarnings("Duplicates")
    private TrackingSocket performOnMessageInvocation(TrackingSocket socket, Function<EndpointFunctions, Void> func) throws Exception
    {
        // Establish endpoint function
        JsrServerEndpointFunctions endpointFunctions = new JsrServerEndpointFunctions(
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

    @ServerEndpoint("/msg")
    public static class MessageStreamSocket extends TrackingSocket
    {
        @OnMessage
        public void onMessage(Reader stream)
        {
            try
            {
                String msg = IO.toString(stream);
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
            endpoint.onText(new TextFrame().setPayload("Hello World").setFin(true), new FrameCallback.Adapter());
            return null;
        });
        socket.assertEvent("onMessage(MessageReader) = \"Hello World\"");
    }
    
    @ServerEndpoint("/msg/{param}")
    public static class MessageStreamParamSocket extends TrackingSocket
    {
        @OnMessage
        public String onMessage(Reader stream, @PathParam("param") String param) throws IOException
        {
            try
            {
                String msg = IO.toString(stream);
                addEvent("onMessage(%s,%s) = \"%s\"", stream.getClass().getSimpleName(), param, msg);
                return msg;
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        }
    }
    
    @Test
    public void testInvokeMessageStreamParam() throws Exception
    {
        uriParams.put("param", "foo");
        TrackingSocket socket = performOnMessageInvocation(new MessageStreamParamSocket(), (endpoint) ->
        {
            endpoint.onText(new TextFrame().setPayload("Hello World").setFin(true), new FrameCallback.Adapter());
            return null;
        });
        socket.assertEvent("onMessage(MessageReader,foo) = \"Hello World\"");
    }
    
}
