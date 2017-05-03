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

package org.eclipse.jetty.websocket.tests.server;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.tests.ParserCapture;
import org.junit.Test;

/**
 * Test simulating a client that talks too quickly.
 * <p>
 * This is mainly for the {@link org.eclipse.jetty.io.Connection.UpgradeTo} logic within
 * the {@link org.eclipse.jetty.websocket.common.io.AbstractWebSocketConnection} implementation.
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
        ByteBuffer buf = ByteBuffer.allocate(4096);
        
        // Create Upgrade Request Header
        String upgradeRequest = generateUpgradeRequest("/");
        ByteBuffer upgradeRequestBytes = BufferUtil.toBuffer(upgradeRequest.toString(), StandardCharsets.UTF_8);
        BufferUtil.put(upgradeRequestBytes, buf);
        
        // Create A few WebSocket Frames
        List<WebSocketFrame> frames = new ArrayList<>();
        frames.add(new TextFrame().setPayload("Hello 1"));
        frames.add(new TextFrame().setPayload("Hello 2"));
        frames.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        generator.generate(buf, frames);
        
        // Send this buffer to the server
        LocalConnector.LocalEndPoint endPoint = newLocalConnection();
    
        BufferUtil.flipToFlush(buf,0);
        performUpgrade(endPoint, buf);
        
        // Let server know that client is done sending
        endPoint.addInputEOF();
        
        // Wait for server to close
        endPoint.waitUntilClosed();
        
        // Get the server send echo bytes
        ByteBuffer wsIncoming = endPoint.getOutput();
        
        // Parse those bytes into frames
        ParserCapture capture = new ParserCapture();
        Parser parser = newClientParser(capture);
        parser.parse(wsIncoming);
        
        // Validate echoed frames
        WebSocketFrame incomingFrame;
        incomingFrame = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Incoming Frame.op", incomingFrame.getOpCode(), is(OpCode.TEXT));
        assertThat("Incoming Frame.payload", incomingFrame.getPayloadAsUTF8(), is("Hello 1"));
        incomingFrame = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Incoming Frame.op", incomingFrame.getOpCode(), is(OpCode.TEXT));
        assertThat("Incoming Frame.payload", incomingFrame.getPayloadAsUTF8(), is("Hello 2"));
    }
    
}
