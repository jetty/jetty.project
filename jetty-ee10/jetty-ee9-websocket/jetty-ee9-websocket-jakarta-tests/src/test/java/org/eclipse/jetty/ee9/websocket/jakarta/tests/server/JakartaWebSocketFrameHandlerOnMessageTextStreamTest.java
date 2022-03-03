//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.jakarta.tests.server;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import jakarta.websocket.OnMessage;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.ee9.websocket.jakarta.common.JakartaWebSocketFrameHandler;
import org.eclipse.jetty.ee9.websocket.jakarta.common.UpgradeRequest;
import org.eclipse.jetty.ee9.websocket.jakarta.common.UpgradeRequestAdapter;
import org.eclipse.jetty.ee9.websocket.jakarta.tests.WSEventTracker;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class JakartaWebSocketFrameHandlerOnMessageTextStreamTest extends AbstractJakartaWebSocketServerFrameHandlerTest
{
    private static final WebSocketComponents components = new WebSocketComponents();

    @BeforeAll
    public static void beforeAll() throws Exception
    {
        components.start();
    }

    @AfterAll
    public static void afterAll() throws Exception
    {
        components.stop();
    }

    @SuppressWarnings("Duplicates")
    private <T extends WSEventTracker> T performOnMessageInvocation(T socket, Consumer<JakartaWebSocketFrameHandler> func) throws Exception
    {
        URI uri = URI.create("http://localhost:8080/msg/foo");
        UpgradeRequest request = new UpgradeRequestAdapter(uri, uri.getPath());

        // Establish endpoint function
        JakartaWebSocketFrameHandler frameHandler = container.newFrameHandler(socket, request);
        frameHandler.onOpen(new CoreSession.Empty()
        {
            @Override
            public WebSocketComponents getWebSocketComponents()
            {
                return components;
            }
        }, Callback.NOOP);
        func.accept(frameHandler);
        return socket;
    }

    @ServerEndpoint("/msg")
    public static class MessageStreamSocket extends WSEventTracker
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
        MessageStreamSocket socket = performOnMessageInvocation(new MessageStreamSocket(), (endpoint) ->
        {
            try
            {
                endpoint.onFrame(new Frame(OpCode.TEXT).setPayload("Hello World").setFin(true), Callback.NOOP);
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        });
        String msg = socket.events.poll(1, TimeUnit.SECONDS);
        assertThat("Message", msg, is("onMessage(MessageReader) = \"Hello World\""));
    }

    @ServerEndpoint("/msg/{param}")
    public static class MessageStreamParamSocket extends WSEventTracker
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
        MessageStreamParamSocket socket = performOnMessageInvocation(new MessageStreamParamSocket(), (endpoint) ->
        {
            try
            {
                endpoint.onFrame(new Frame(OpCode.TEXT).setPayload("Hello World").setFin(true), Callback.NOOP);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        });
        String msg = socket.events.poll(1, TimeUnit.SECONDS);
        assertThat("Message", msg, is("onMessage(MessageReader,foo) = \"Hello World\""));
    }
}
