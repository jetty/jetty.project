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

import java.util.concurrent.TimeUnit;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.jsr356.sockets.TrackingSocket;
import org.junit.Test;

public class JavaxWebSocketLocalEndpoint_OnCloseTest extends AbstractJavaxWebSocketLocalEndpointTest
{
    private static final String EXPECTED_REASON = "CloseReason[1000,Normal]";
    
    private void assertOnCloseInvocation(TrackingSocket socket, String expectedEventFormat, Object... args) throws Exception
    {
        JavaxWebSocketLocalEndpointFactory factory = new JavaxWebSocketLocalEndpointFactory();
        BasicEndpointConfig config = new BasicEndpointConfig();
        ConfiguredEndpoint endpoint = new ConfiguredEndpoint(socket, config);
        JavaxWebSocketSession session = newSession();

        JavaxWebSocketLocalEndpoint localEndpoint = factory.createLocalEndpoint(endpoint,
                session, container.getPolicy(), container.getExecutor());

        // These invocations are the same for all tests
        localEndpoint.onOpen();
        CloseStatus closeInfo = new CloseStatus(WebSocketConstants.NORMAL, "Normal");
        localEndpoint.onClose(closeInfo);
        
        String event = socket.events.poll(1, TimeUnit.SECONDS);
        assertThat("Event", event, is(String.format(expectedEventFormat, args)));
    }

    @ClientEndpoint
    public static class CloseSocket extends TrackingSocket
    {
        @OnClose
        public void OnClose()
        {
            addEvent("OnClose()");
        }
    }

    @Test
    public void testInvokeClose() throws Exception
    {
        assertOnCloseInvocation(new CloseSocket(), "OnClose()");
    }

    @ClientEndpoint
    public static class CloseSessionSocket extends TrackingSocket
    {
        @OnClose
        public void OnClose(Session session)
        {
            addEvent("OnClose(%s)", session);
        }
    }

    @Test
    public void testInvokeCloseSession() throws Exception
    {
        assertOnCloseInvocation(new CloseSessionSocket(),
                "OnClose(JsrSession[CLIENT,%s,DummyConnection])",
                CloseSessionSocket.class.getName());
    }

    @ClientEndpoint
    public static class CloseReasonSocket extends TrackingSocket
    {
        @OnClose
        public void OnClose(CloseReason reason)
        {
            addEvent("OnClose(%s)", reason);
        }
    }

    @Test
    public void testInvokeCloseReason() throws Exception
    {
        assertOnCloseInvocation(new CloseReasonSocket(),
                "OnClose(%s)", EXPECTED_REASON);
    }

    @ClientEndpoint
    public static class CloseSessionReasonSocket extends TrackingSocket
    {
        @OnClose
        public void OnClose(Session session, CloseReason reason)
        {
            addEvent("OnClose(%s, %s)", session, reason);
        }
    }

    @Test
    public void testInvokeCloseSessionReason() throws Exception
    {
        assertOnCloseInvocation(new CloseSessionReasonSocket(),
                "OnClose(JsrSession[CLIENT,%s,DummyConnection], %s)",
                CloseSessionReasonSocket.class.getName(), EXPECTED_REASON);
    }

    @ClientEndpoint
    public static class CloseReasonSessionSocket extends TrackingSocket
    {
        @OnClose
        public void OnClose(CloseReason reason, Session session)
        {
            addEvent("OnClose(%s, %s)", reason, session);
        }
    }

    @Test
    public void testInvokeCloseReasonSession() throws Exception
    {
        assertOnCloseInvocation(new CloseReasonSessionSocket(),
                "OnClose(%s, JsrSession[CLIENT,%s,DummyConnection])",
                EXPECTED_REASON, CloseReasonSessionSocket.class.getName());
    }
}
