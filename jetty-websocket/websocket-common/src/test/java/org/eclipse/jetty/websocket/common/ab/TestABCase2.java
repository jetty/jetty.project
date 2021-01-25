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

package org.eclipse.jetty.websocket.common.ab;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.PingFrame;
import org.eclipse.jetty.websocket.common.test.ByteBufferAssert;
import org.eclipse.jetty.websocket.common.test.IncomingFramesCapture;
import org.eclipse.jetty.websocket.common.test.UnitGenerator;
import org.eclipse.jetty.websocket.common.test.UnitParser;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TestABCase2
{
    private WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);

    @Test
    public void testGenerate125OctetPingCase24()
    {
        byte[] bytes = new byte[125];

        for (int i = 0; i < bytes.length; ++i)
        {
            bytes[i] = Integer.valueOf(Integer.toOctalString(i)).byteValue();
        }

        WebSocketFrame pingFrame = new PingFrame().setPayload(bytes);

        ByteBuffer actual = UnitGenerator.generate(pingFrame);

        ByteBuffer expected = ByteBuffer.allocate(bytes.length + 32);

        expected.put(new byte[]
            {(byte)0x89});

        byte b = 0x00; // no masking
        b |= bytes.length & 0x7F;
        expected.put(b);
        expected.put(bytes);

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    @Test
    public void testGenerateBinaryPingCase23()
    {
        byte[] bytes = new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};

        PingFrame pingFrame = new PingFrame().setPayload(bytes);

        ByteBuffer actual = UnitGenerator.generate(pingFrame);

        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
            {(byte)0x89});

        byte b = 0x00; // no masking
        b |= bytes.length & 0x7F;
        expected.put(b);
        expected.put(bytes);

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    @Test
    public void testGenerateEmptyPingCase21()
    {
        WebSocketFrame pingFrame = new PingFrame();

        ByteBuffer actual = UnitGenerator.generate(pingFrame);

        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
            {(byte)0x89, (byte)0x00});

        expected.flip();

        ByteBufferAssert.assertEquals(expected, actual, "buffers do not match");
    }

    @Test
    public void testGenerateHelloPingCase22()
    {
        String message = "Hello, world!";
        byte[] messageBytes = StringUtil.getUtf8Bytes(message);

        PingFrame pingFrame = new PingFrame().setPayload(messageBytes);

        ByteBuffer actual = UnitGenerator.generate(pingFrame);

        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
            {(byte)0x89});

        byte b = 0x00; // no masking
        b |= messageBytes.length & 0x7F;
        expected.put(b);
        expected.put(messageBytes);

        expected.flip();

        ByteBufferAssert.assertEquals(expected, actual, "buffers do not match");
    }

    @Test
    public void testGenerateOversizedBinaryPingCase25A()
    {
        byte[] bytes = new byte[126];
        Arrays.fill(bytes, (byte)0x00);

        PingFrame pingFrame = new PingFrame();
        assertThrows(WebSocketException.class, () -> pingFrame.setPayload(ByteBuffer.wrap(bytes)));
    }

    @Test
    public void testGenerateOversizedBinaryPingCase25B()
    {
        byte[] bytes = new byte[126];
        Arrays.fill(bytes, (byte)0x00);

        PingFrame pingFrame = new PingFrame();
        assertThrows(WebSocketException.class, () ->
            pingFrame.setPayload(ByteBuffer.wrap(bytes)));
    }

    @Test
    public void testParse125OctetPingCase24()
    {
        byte[] bytes = new byte[125];

        for (int i = 0; i < bytes.length; ++i)
        {
            bytes[i] = Integer.valueOf(Integer.toOctalString(i)).byteValue();
        }

        ByteBuffer expected = ByteBuffer.allocate(bytes.length + 32);

        expected.put(new byte[]
            {(byte)0x89});

        byte b = 0x00; // no masking
        b |= bytes.length & 0x7F;
        expected.put(b);
        expected.put(bytes);

        expected.flip();

        Parser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        capture.assertHasFrame(OpCode.PING, 1);

        Frame pActual = capture.getFrames().poll();
        assertThat("PingFrame.payloadLength", pActual.getPayloadLength(), is(bytes.length));
        assertEquals(bytes.length, pActual.getPayloadLength(), "PingFrame.payload");
    }

    @Test
    public void testParseBinaryPingCase23()
    {
        byte[] bytes = new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};

        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
            {(byte)0x89});

        byte b = 0x00; // no masking
        b |= bytes.length & 0x7F;
        expected.put(b);
        expected.put(bytes);

        expected.flip();

        Parser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        capture.assertHasFrame(OpCode.PING, 1);

        Frame pActual = capture.getFrames().poll();
        assertThat("PingFrame.payloadLength", pActual.getPayloadLength(), is(bytes.length));
        assertEquals(bytes.length, pActual.getPayloadLength(), "PingFrame.payload");
    }

    @Test
    public void testParseEmptyPingCase21()
    {
        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
            {(byte)0x89, (byte)0x00});

        expected.flip();

        Parser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        capture.assertHasFrame(OpCode.PING, 1);

        Frame pActual = capture.getFrames().poll();
        assertThat("PingFrame.payloadLength", pActual.getPayloadLength(), is(0));
        assertEquals(0, pActual.getPayloadLength(), "PingFrame.payload");
    }

    @Test
    public void testParseHelloPingCase22()
    {
        String message = "Hello, world!";
        byte[] messageBytes = message.getBytes();

        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
            {(byte)0x89});

        byte b = 0x00; // no masking
        b |= messageBytes.length & 0x7F;
        expected.put(b);
        expected.put(messageBytes);

        expected.flip();

        Parser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        capture.assertHasFrame(OpCode.PING, 1);

        Frame pActual = capture.getFrames().poll();
        assertThat("PingFrame.payloadLength", pActual.getPayloadLength(), is(message.length()));
        assertEquals(message.length(), pActual.getPayloadLength(), "PingFrame.payload");
    }

    @Test
    public void testParseOversizedBinaryPingCase25()
    {
        byte[] bytes = new byte[126];
        Arrays.fill(bytes, (byte)0x00);

        ByteBuffer expected = ByteBuffer.allocate(bytes.length + Generator.MAX_HEADER_LENGTH);

        byte b;

        // fin + op
        b = 0x00;
        b |= 0x80; // fin on
        b |= 0x09; // ping
        expected.put(b);

        // mask + len
        b = 0x00;
        b |= 0x00; // no masking
        b |= 0x7E; // 2 byte len
        expected.put(b);

        // 2 byte len
        expected.putChar((char)bytes.length);

        // payload
        expected.put(bytes);

        expected.flip();

        UnitParser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);

        ProtocolException x = assertThrows(ProtocolException.class, () -> parser.parseQuietly(expected));
        assertThat(x.getMessage(), containsString("Invalid control frame payload length"));
    }
}
