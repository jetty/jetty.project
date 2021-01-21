//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.io.LocalWebSocketConnection;
import org.eclipse.jetty.websocket.common.test.OutgoingFramesCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class WebSocketRemoteEndpointTest
{
    public ByteBufferPool bufferPool = new MappedByteBufferPool();

    @Test
    public void testTextBinaryText(TestInfo testinfo) throws IOException
    {
        String id = testinfo.getDisplayName();
        LocalWebSocketConnection conn = new LocalWebSocketConnection(id, bufferPool);
        OutgoingFramesCapture outgoing = new OutgoingFramesCapture();
        WebSocketRemoteEndpoint remote = new WebSocketRemoteEndpoint(conn, outgoing);
        conn.opening();
        conn.opened();

        // Start text message
        remote.sendPartialString("Hello ", false);

        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
        {
            // Attempt to start Binary Message
            ByteBuffer bytes = ByteBuffer.wrap(new byte[]
                {0, 1, 2});
            remote.sendPartialBytes(bytes, false);
        });
        assertThat("Exception", e.getMessage(), containsString("Cannot send"));

        // End text message
        remote.sendPartialString("World!", true);
    }

    @Test
    public void testTextPingText(TestInfo testinfo) throws IOException
    {
        String id = testinfo.getDisplayName();
        LocalWebSocketConnection conn = new LocalWebSocketConnection(id, bufferPool);
        OutgoingFramesCapture outgoing = new OutgoingFramesCapture();
        WebSocketRemoteEndpoint remote = new WebSocketRemoteEndpoint(conn, outgoing);
        conn.opening();
        conn.opened();

        // Start text message
        remote.sendPartialString("Hello ", false);

        // Attempt to send Ping Message
        remote.sendPing(ByteBuffer.wrap(new byte[]
            {0}));

        // End text message
        remote.sendPartialString("World!", true);
    }

    /**
     * Ensure that WebSocketRemoteEndpoint honors the correct order of websocket frames.
     *
     * @see <a href="https://github.com/eclipse/jetty.project/issues/2491">eclipse/jetty.project#2491</a>
     */
    @Test
    public void testLargeSmallText(TestInfo testInfo) throws ExecutionException, InterruptedException
    {
        LocalWebSocketConnection conn = new LocalWebSocketConnection(testInfo.getDisplayName(), bufferPool);
        OutgoingFrames orderingAssert = new SaneFrameOrderingAssertion();
        WebSocketRemoteEndpoint remote = new WebSocketRemoteEndpoint(conn, orderingAssert);
        conn.opening();
        conn.opened();

        int largeMessageSize = 60000;
        byte[] buf = new byte[largeMessageSize];
        Arrays.fill(buf, (byte)'x');
        String largeMessage = new String(buf, UTF_8);

        int messageCount = 10000;

        for (int i = 0; i < messageCount; i++)
        {
            Future<Void> fut;
            if (i % 2 == 0)
                fut = remote.sendStringByFuture(largeMessage);
            else
                fut = remote.sendStringByFuture("Short Message: " + i);
            fut.get();
        }
    }
}
