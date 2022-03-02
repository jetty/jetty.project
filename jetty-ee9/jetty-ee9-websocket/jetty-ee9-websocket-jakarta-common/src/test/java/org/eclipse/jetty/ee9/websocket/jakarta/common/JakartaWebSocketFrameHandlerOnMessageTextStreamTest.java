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

package org.eclipse.jetty.ee9.websocket.jakarta.common;

import java.io.IOException;
import java.io.Reader;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.OnMessage;
import org.eclipse.jetty.ee9.websocket.jakarta.common.sockets.TrackingSocket;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class JakartaWebSocketFrameHandlerOnMessageTextStreamTest extends AbstractJakartaWebSocketFrameHandlerTest
{
    @SuppressWarnings("Duplicates")
    private TrackingSocket performOnMessageInvocation(TrackingSocket socket, Function<JakartaWebSocketFrameHandler, Void> func) throws Exception
    {
        JakartaWebSocketFrameHandler localEndpoint = newJakartaFrameHandler(socket);

        // This invocation is the same for all tests
        localEndpoint.onOpen(coreSession, Callback.NOOP);

        func.apply(localEndpoint);

        return socket;
    }

    @ClientEndpoint
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
            try
            {
                endpoint.onFrame(new Frame(OpCode.TEXT).setPayload("Hello World").setFin(true), Callback.NOOP);
            }
            catch (Exception e)
            {
                throw new RuntimeException("Unexpected error", e);
            }
            return null;
        });
        String event = socket.events.poll(1, TimeUnit.SECONDS);
        assertThat("Event", event, is("onMessage(MessageReader) = \"Hello World\""));
    }
}
