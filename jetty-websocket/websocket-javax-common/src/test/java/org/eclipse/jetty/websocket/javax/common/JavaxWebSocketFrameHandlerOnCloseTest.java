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

package org.eclipse.jetty.websocket.javax.common;

import java.util.concurrent.TimeUnit;
import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.Session;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.javax.common.sockets.TrackingSocket;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;

public class JavaxWebSocketFrameHandlerOnCloseTest extends AbstractJavaxWebSocketFrameHandlerTest
{
    private static final String EXPECTED_REASON = "CloseReason[1000,Normal]";

    private void assertOnCloseInvocation(TrackingSocket socket, Matcher<String> eventMatcher) throws Exception
    {
        JavaxWebSocketFrameHandler localEndpoint = newJavaxFrameHandler(socket);

        // These invocations are the same for all tests
        localEndpoint.onOpen(coreSession, Callback.NOOP);
        CloseStatus status = new CloseStatus(CloseStatus.NORMAL, "Normal");
        Frame closeFrame = status.toFrame();
        localEndpoint.onFrame(closeFrame, Callback.from(() ->
                localEndpoint.onClosed(status, Callback.NOOP),
            t ->
            {
                throw new RuntimeException(t);
            }
        ));
        String event = socket.events.poll(10, TimeUnit.SECONDS);
        assertThat("Event", event, eventMatcher);
    }

    @ClientEndpoint
    public static class CloseSocket extends TrackingSocket
    {
        @OnClose
        public void onClose()
        {
            addEvent("onClose()");
        }
    }

    @Test
    public void testInvokeClose() throws Exception
    {
        assertOnCloseInvocation(new CloseSocket(), containsString("onClose()"));
    }

    @ClientEndpoint
    public static class CloseSessionSocket extends TrackingSocket
    {
        @OnClose
        public void onClose(Session session)
        {
            addEvent("onClose(%s)", session);
        }
    }

    @Test
    public void testInvokeCloseSession() throws Exception
    {
        assertOnCloseInvocation(new CloseSessionSocket(),
            allOf(
                containsString("onClose(JavaxWebSocketSession@"),
                containsString(CloseSessionSocket.class.getName())
            ));
    }

    @ClientEndpoint
    public static class CloseReasonSocket extends TrackingSocket
    {
        @OnClose
        public void onClose(CloseReason reason)
        {
            addEvent("onClose(%s)", reason);
        }
    }

    @Test
    public void testInvokeCloseReason() throws Exception
    {
        assertOnCloseInvocation(new CloseReasonSocket(),
            containsString("onClose(" + EXPECTED_REASON + ")"));
    }

    @ClientEndpoint
    public static class CloseSessionReasonSocket extends TrackingSocket
    {
        @OnClose
        public void onClose(Session session, CloseReason reason)
        {
            addEvent("onClose(%s, %s)", session, reason);
        }
    }

    @Test
    public void testInvokeCloseSessionReason() throws Exception
    {
        assertOnCloseInvocation(new CloseSessionReasonSocket(),
            allOf(
                containsString("onClose(JavaxWebSocketSession@"),
                containsString(CloseSessionReasonSocket.class.getName())
            ));
    }

    @ClientEndpoint
    public static class CloseReasonSessionSocket extends TrackingSocket
    {
        @OnClose
        public void onClose(CloseReason reason, Session session)
        {
            addEvent("onClose(%s, %s)", reason, session);
        }
    }

    @Test
    public void testInvokeCloseReasonSession() throws Exception
    {
        assertOnCloseInvocation(new CloseReasonSessionSocket(),
            allOf(
                containsString("onClose(" + EXPECTED_REASON),
                containsString(CloseReasonSessionSocket.class.getName())
            ));
    }
}
