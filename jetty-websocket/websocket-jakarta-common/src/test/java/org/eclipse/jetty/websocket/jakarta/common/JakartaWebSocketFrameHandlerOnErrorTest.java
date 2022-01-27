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

package org.eclipse.jetty.websocket.jakarta.common;

import java.util.concurrent.TimeUnit;

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.OnError;
import jakarta.websocket.Session;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.jakarta.common.sockets.TrackingSocket;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;

public class JakartaWebSocketFrameHandlerOnErrorTest extends AbstractJakartaWebSocketFrameHandlerTest
{
    private static final String EXPECTED_THROWABLE = "java.lang.RuntimeException: From Testcase";

    private void assertOnErrorInvocation(TrackingSocket socket, Matcher<String> eventMatcher) throws Exception
    {
        JakartaWebSocketFrameHandler localEndpoint = newJakartaFrameHandler(socket);

        // These invocations are the same for all tests
        localEndpoint.onOpen(coreSession, Callback.NOOP);
        localEndpoint.onError(new RuntimeException("From Testcase"), Callback.NOOP);
        String event = socket.events.poll(1, TimeUnit.SECONDS);
        assertThat("Event", event, eventMatcher);
    }

    @ClientEndpoint
    public static class ErrorSessionThrowableSocket extends TrackingSocket
    {
        @OnError
        public void onError(Session session, Throwable cause)
        {
            addEvent("onError(%s, %s)", session, cause);
        }
    }

    @Test
    public void testInvokeErrorSessionThrowable() throws Exception
    {
        assertOnErrorInvocation(new ErrorSessionThrowableSocket(),
            allOf(
                containsString("onError(JakartaWebSocketSession@"),
                containsString(ErrorSessionThrowableSocket.class.getName()),
                containsString(EXPECTED_THROWABLE)
            ));
    }

    @ClientEndpoint
    public static class ErrorThrowableSocket extends TrackingSocket
    {
        @OnError
        public void onError(Throwable cause)
        {
            addEvent("onError(%s)", cause);
        }
    }

    @Test
    public void testInvokeErrorThrowable() throws Exception
    {
        assertOnErrorInvocation(new ErrorThrowableSocket(),
            allOf(
                containsString("onError("),
                containsString(EXPECTED_THROWABLE)
            ));
    }

    @ClientEndpoint
    public static class ErrorThrowableSessionSocket extends TrackingSocket
    {
        @OnError
        public void onError(Throwable cause, Session session)
        {
            addEvent("onError(%s, %s)", cause, session);
        }
    }

    @Test
    public void testInvokeErrorThrowableSession() throws Exception
    {
        assertOnErrorInvocation(new ErrorThrowableSessionSocket(),
            allOf(
                containsString("onError("),
                containsString(ErrorThrowableSessionSocket.class.getName()),
                containsString(EXPECTED_THROWABLE)
            ));
    }
}
