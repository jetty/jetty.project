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
import javax.websocket.OnError;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.jsr356.sockets.TrackingSocket;
import org.hamcrest.Matcher;
import org.junit.Test;

public class JavaxWebSocketFrameHandler_OnErrorTest extends AbstractJavaxWebSocketFrameHandlerTest
{
    private static final String EXPECTED_THROWABLE = "java.lang.RuntimeException: From Testcase";
    
    private void assertOnErrorInvocation(TrackingSocket socket, Matcher<String> eventMatcher) throws Exception
    {
        JavaxWebSocketFrameHandler localEndpoint = newJavaxFrameHandler(socket);

        // These invocations are the same for all tests
        localEndpoint.onOpen(channel);
        localEndpoint.onError(new RuntimeException("From Testcase"));
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
                        containsString("onError(JavaxWebSocketSession@"),
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
