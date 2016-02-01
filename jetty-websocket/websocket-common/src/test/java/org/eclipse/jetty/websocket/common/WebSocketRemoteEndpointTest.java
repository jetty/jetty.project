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

package org.eclipse.jetty.websocket.common;

import static org.hamcrest.Matchers.containsString;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.common.io.LocalWebSocketConnection;
import org.eclipse.jetty.websocket.common.test.LeakTrackingBufferPoolRule;
import org.eclipse.jetty.websocket.common.test.OutgoingFramesCapture;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class WebSocketRemoteEndpointTest
{
    @Rule
    public TestName testname = new TestName();

    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule("WebSocketRemoteEndpoint");

    @Test
    public void testTextBinaryText() throws IOException
    {
        LocalWebSocketConnection conn = new LocalWebSocketConnection(testname,bufferPool);
        OutgoingFramesCapture outgoing = new OutgoingFramesCapture();
        WebSocketRemoteEndpoint remote = new WebSocketRemoteEndpoint(conn,outgoing);
        conn.connect();
        conn.open();

        // Start text message
        remote.sendPartialString("Hello ",false);

        try
        {
            // Attempt to start Binary Message
            ByteBuffer bytes = ByteBuffer.wrap(new byte[]
                    { 0, 1, 2 });
            remote.sendPartialBytes(bytes,false);
            Assert.fail("Expected " + IllegalStateException.class.getName());
        }
        catch (IllegalStateException e)
        {
            // Expected path
            Assert.assertThat("Exception",e.getMessage(),containsString("Cannot send"));
        }

        // End text message
        remote.sendPartialString("World!",true);
    }

    @Test
    public void testTextPingText() throws IOException
    {
        LocalWebSocketConnection conn = new LocalWebSocketConnection(testname,bufferPool);
        OutgoingFramesCapture outgoing = new OutgoingFramesCapture();
        WebSocketRemoteEndpoint remote = new WebSocketRemoteEndpoint(conn,outgoing);
        conn.connect();
        conn.open();

        // Start text message
        remote.sendPartialString("Hello ",false);

        // Attempt to send Ping Message
        remote.sendPing(ByteBuffer.wrap(new byte[]
                { 0 }));

        // End text message
        remote.sendPartialString("World!",true);
    }
}
