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

package org.eclipse.jetty.websocket.tests.server;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.internal.WebSocketConnection;
import org.eclipse.jetty.websocket.tests.ParserCapture;
import org.eclipse.jetty.websocket.tests.UpgradeUtils;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

/**
 * Test simulating a client that talks too quickly.
 * <p>
 * This is mainly for the {@link org.eclipse.jetty.io.Connection.UpgradeTo} logic within
 * the {@link WebSocketConnection} implementation.
 * </p>
 * <p>
 * There is a class of client that will send the GET+Upgrade Request along with a few websocket frames in a single
 * network packet. This test attempts to perform this behavior as close as possible.
 * </p>
 */
public class ConnectionUpgradeToBufferTest extends AbstractLocalServerCase
{
    @Test
    public void testUpgradeWithSmallFrames() throws Exception
    {
        assertTimeout(Duration.ofMillis(10000), ()->
        {
            ByteBuffer buf = ByteBuffer.allocate(4096);

            // Create Upgrade Request Header
            String upgradeRequest = UpgradeUtils.generateUpgradeRequest("/");
            ByteBuffer upgradeRequestBytes = BufferUtil.toBuffer(upgradeRequest.toString(), StandardCharsets.UTF_8);
            BufferUtil.put(upgradeRequestBytes, buf);

            // Create A few WebSocket Frames
            List<Frame> frames = new ArrayList<>();
            frames.add(new Frame(OpCode.TEXT).setPayload("Hello 1"));
            frames.add(new Frame(OpCode.TEXT).setPayload("Hello 2"));
            frames.add(CloseStatus.toFrame(StatusCode.NORMAL.getCode()));

            generator.generate(buf, frames);

            // Send this buffer to the server
            LocalConnector.LocalEndPoint endPoint = server.newLocalConnection();

            BufferUtil.flipToFlush(buf, 0);
            ParsedResponse response = performUpgrade(endPoint, buf);

            // Parse received bytes
            ParserCapture capture = new ParserCapture(newClientParser());

            if (BufferUtil.hasContent(response.remainingBuffer))
            {
                capture.parse(response.remainingBuffer);
            }

            // parse bytes seen till close
            do
            {
                ByteBuffer wsIncoming = endPoint.takeOutput();
                if (wsIncoming.hasRemaining())
                {
                    capture.parse(wsIncoming);
                }
            }
            while (!capture.closed);

            // Validate echoed frames
            Frame incomingFrame;
            incomingFrame = capture.framesQueue.poll(1, TimeUnit.SECONDS);
            assertThat("Incoming Frame[0]", incomingFrame, notNullValue());
            assertThat("Incoming Frame[0].op", incomingFrame.getOpCode(), is(OpCode.TEXT));
            assertThat("Incoming Frame[0].payload", incomingFrame.getPayloadAsUTF8(), is("Hello 1"));
            incomingFrame = capture.framesQueue.poll(1, TimeUnit.SECONDS);
            assertThat("Incoming Frame[1]", incomingFrame, notNullValue());
            assertThat("Incoming Frame[1].op", incomingFrame.getOpCode(), is(OpCode.TEXT));
            assertThat("Incoming Frame[1].payload", incomingFrame.getPayloadAsUTF8(), is("Hello 2"));
        });
    }
}
