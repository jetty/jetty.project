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

package org.eclipse.jetty.websocket.tests;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.LeakTrackingByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.common.WebSocketRemoteEndpoint;
import org.eclipse.jetty.websocket.common.WebSocketSession;
import org.eclipse.jetty.websocket.common.scopes.SimpleContainerScope;
import org.eclipse.jetty.websocket.common.util.LifeCycleScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

public class WebSocketRemoteEndpointTest
{
    @WebSocket
    public static class DummySocket
    {
        /* does nothing */
    }
    

    public LeakTrackingByteBufferPool bufferPool = new LeakTrackingByteBufferPool(new MappedByteBufferPool());

    @Test
    public void testTextBinaryText( TestInfo testInfo ) throws Exception
    {
        SimpleContainerScope container = new SimpleContainerScope(WebSocketPolicy.newServerPolicy());
        WebSocketSession session = new LocalWebSocketSession(container, testInfo, new DummySocket());
        OutgoingFramesCapture outgoing = new OutgoingFramesCapture();
        WebSocketRemoteEndpoint remote = new WebSocketRemoteEndpoint(session,outgoing);
        try(LifeCycleScope sessionScope = new LifeCycleScope(session))
        {
            session.connect();
            session.open();
    
            // Start text message
            remote.sendPartialString("Hello ", false);
    
            try
            {
                // Attempt to start Binary Message
                ByteBuffer bytes = ByteBuffer.wrap(new byte[] {0, 1, 2});
                remote.sendPartialBytes(bytes, false);
                fail("Expected " + IllegalStateException.class.getName());
            }
            catch (IllegalStateException e)
            {
                // Expected path
                assertThat("Exception", e.getMessage(), containsString("Cannot send"));
            }
    
            // End text message
            remote.sendPartialString("World!", true);
        }
    }

    @Test
    public void testTextPingText(TestInfo testInfo) throws Exception
    {
        SimpleContainerScope container = new SimpleContainerScope(WebSocketPolicy.newServerPolicy());
        WebSocketSession session = new LocalWebSocketSession(container, testInfo, new DummySocket());
        OutgoingFramesCapture outgoing = new OutgoingFramesCapture();
        WebSocketRemoteEndpoint remote = new WebSocketRemoteEndpoint(session,outgoing);
        try(LifeCycleScope sessionScope = new LifeCycleScope(session))
        {
            session.connect();
            session.open();
    
            // Start text message
            remote.sendPartialString("Hello ", false);
    
            // Attempt to send Ping Message
            remote.sendPing(ByteBuffer.wrap(new byte[]
                    {0}));
    
            // End text message
            remote.sendPartialString("World!", true);
        }
    }
}
