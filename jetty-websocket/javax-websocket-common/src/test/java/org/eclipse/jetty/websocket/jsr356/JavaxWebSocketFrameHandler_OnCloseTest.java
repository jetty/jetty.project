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

package org.eclipse.jetty.websocket.jsr356;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;

import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.Session;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.jsr356.sockets.TrackingSocket;
import org.hamcrest.Matcher;
import org.junit.Test;

public class JavaxWebSocketFrameHandler_OnCloseTest extends AbstractJavaxWebSocketFrameHandlerTest
{
    private static final String EXPECTED_REASON = "CloseReason[1000,Normal]";
    
    private void assertOnCloseInvocation(TrackingSocket socket, Matcher<String> eventMatcher) throws Exception
    {
        JavaxWebSocketFrameHandler localEndpoint = newJavaxFrameHandler(socket);

        // These invocations are the same for all tests
        localEndpoint.onOpen(channel);
        CloseFrame closeFrame = new CloseFrame().setPayload(WebSocketConstants.NORMAL, "Normal");
        localEndpoint.onFrame(closeFrame, Callback.NOOP);
        
        String event = socket.events.poll(1, TimeUnit.SECONDS);
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
