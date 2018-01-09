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

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpoint;
import javax.websocket.OnError;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.jsr356.sockets.TrackingSocket;
import org.junit.Test;

public class JavaxWebSocketLocalEndpoint_OnErrorTest extends AbstractJavaxWebSocketLocalEndpointTest
{
    private static final String EXPECTED_THROWABLE = "java.lang.RuntimeException: From Testcase";
    
    private void assertOnErrorInvocation(TrackingSocket socket, String expectedEventFormat, Object... args) throws Exception
    {
        JavaxWebSocketLocalEndpoint localEndpoint = createLocalEndpoint(socket);

        // These invocations are the same for all tests
        localEndpoint.onOpen();
        localEndpoint.onError(new RuntimeException("From Testcase"));
        String event = socket.events.poll(1, TimeUnit.SECONDS);
        assertThat("Event", event, is(String.format(expectedEventFormat, args)));
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
                "onError(JavaxWebSocketSession[CLIENT,%s,DummyConnection], %s)",
                ErrorSessionThrowableSocket.class.getName(), EXPECTED_THROWABLE);
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
                "onError(%s)", EXPECTED_THROWABLE);
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
                "onError(%s, JavaxWebSocketSession[CLIENT,%s,DummyConnection])",
                EXPECTED_THROWABLE,
                ErrorThrowableSessionSocket.class.getName());
    }
}
