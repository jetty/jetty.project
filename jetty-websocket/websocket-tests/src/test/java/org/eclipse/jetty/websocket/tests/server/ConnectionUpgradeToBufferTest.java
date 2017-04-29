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
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.CloseFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.tests.ParserCapture;
import org.eclipse.jetty.websocket.tests.SimpleServletServer;
import org.eclipse.jetty.websocket.tests.servlets.EchoServlet;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

/**
 * Test simulating a client that talks too quickly.
 * <p>
 *     This is mainly for the {@link org.eclipse.jetty.io.Connection.UpgradeTo} logic within
 *     the {@link org.eclipse.jetty.websocket.common.io.AbstractWebSocketConnection} implementation.
 * </p>
 * <p>
 *     There is a class of client that will send the GET+Upgrade Request along with a few websocket frames in a single
 *      network packet. This test attempts to perform this behavior as close as possible.
 * </p>
 */
public class ConnectionUpgradeToBufferTest
{
    private static SimpleServletServer server;
    
    @BeforeClass
    public static void startServer() throws Exception
    {
        server = new SimpleServletServer(new EchoServlet());
        server.start();
    }
    
    @AfterClass
    public static void stopServer() throws Exception
    {
        server.stop();
    }
    
    @Rule
    public TestName testname = new TestName();
    
    @Test
    public void testUpgradeWithSmallFrames() throws Exception
    {
        ByteBuffer buf = ByteBuffer.allocate(4096);
        
        // Create Upgrade Request Header
        StringBuilder upgradeRequest = new StringBuilder();
        upgradeRequest.append("GET / HTTP/1.1\r\n");
        upgradeRequest.append("Host: local\r\n");
        upgradeRequest.append("Connection: Upgrade\r\n");
        upgradeRequest.append("Upgrade: WebSocket\r\n");
        upgradeRequest.append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n");
        upgradeRequest.append("Sec-WebSocket-Origin: ws://local/\r\n");
        upgradeRequest.append("Sec-WebSocket-Protocol: echo\r\n");
        upgradeRequest.append("Sec-WebSocket-Version: 13\r\n");
        upgradeRequest.append("\r\n");
        
        ByteBuffer upgradeRequestBytes = BufferUtil.toBuffer(upgradeRequest.toString(), StandardCharsets.UTF_8);
        BufferUtil.put(upgradeRequestBytes, buf);
    
        // Create A few WebSocket Frames
        TextFrame frame1 = new TextFrame().setPayload("Hello 1");
        TextFrame frame2 = new TextFrame().setPayload("Hello 2");
        CloseFrame closeFrame = new CloseInfo(StatusCode.NORMAL).asFrame();
    
        // Need to set frame mask (as these are client frames)
        byte mask[] = new byte[]{0x11, 0x22, 0x33, 0x44};
        frame1.setMask(mask);
        frame2.setMask(mask);
        closeFrame.setMask(mask);
    
        ByteBufferPool bufferPool = new MappedByteBufferPool();
    
        Generator generator = new Generator(WebSocketPolicy.newClientPolicy(), bufferPool);
        generator.generateWholeFrame(frame1, buf);
        generator.generateWholeFrame(frame2, buf);
        generator.generateWholeFrame(closeFrame, buf);
    
        // Send this buffer to the server
        BufferUtil.flipToFlush(buf, 0);
        LocalConnector connector = server.getLocalConnector();
        LocalConnector.LocalEndPoint endPoint = connector.connect();
        endPoint.addInput(buf);

        // Get response
        ByteBuffer response = endPoint.waitForResponse(false, 1, TimeUnit.SECONDS);
        HttpTester.Response parsedResponse = HttpTester.parseResponse(response);
        
        assertThat("Is Switching Protocols", parsedResponse.getStatus(), is(101));
        assertThat("Is WebSocket Upgrade", parsedResponse.get("Upgrade"), is("WebSocket"));
        
        // Let server know that client is done sending
        endPoint.addInputEOF();
        
        // Wait for server to close
        endPoint.waitUntilClosed();
        
        // Get the server send echo bytes
        ByteBuffer wsIncoming = endPoint.getOutput();
    
        // Parse those bytes into frames
        ParserCapture capture = new ParserCapture();
        Parser parser = new Parser(WebSocketPolicy.newClientPolicy(), bufferPool, capture);
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
