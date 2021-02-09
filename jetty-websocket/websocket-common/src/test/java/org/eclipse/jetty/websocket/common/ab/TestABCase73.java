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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.frames.CloseFrame;
import org.eclipse.jetty.websocket.common.test.ByteBufferAssert;
import org.eclipse.jetty.websocket.common.test.IncomingFramesCapture;
import org.eclipse.jetty.websocket.common.test.UnitGenerator;
import org.eclipse.jetty.websocket.common.test.UnitParser;
import org.eclipse.jetty.websocket.common.util.Hex;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TestABCase73
{
    private WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);

    @Test
    public void testCase731GenerateEmptyClose()
    {
        CloseInfo close = new CloseInfo();

        ByteBuffer actual = UnitGenerator.generate(close.asFrame());

        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
            {(byte)0x88, (byte)0x00});

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    @Test
    public void testCase731ParseEmptyClose()
    {
        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
            {(byte)0x88, (byte)0x00});

        expected.flip();

        Parser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        capture.assertHasFrame(OpCode.CLOSE, 1);

        Frame pActual = capture.getFrames().poll();
        assertThat("CloseFrame.payloadLength", pActual.getPayloadLength(), is(0));
    }

    @Test
    public void testCase732Generate1BytePayloadClose()
    {
        CloseFrame closeFrame = new CloseFrame();
        closeFrame.setPayload(Hex.asByteBuffer("00"));

        assertThrows(ProtocolException.class, () -> UnitGenerator.generate(closeFrame));
    }

    @Test
    public void testCase732Parse1BytePayloadClose()
    {
        ByteBuffer expected = Hex.asByteBuffer("880100");

        UnitParser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        assertThrows(ProtocolException.class, () -> parser.parseQuietly(expected));
    }

    @Test
    public void testCase733GenerateCloseWithStatus()
    {
        CloseInfo close = new CloseInfo(1000);

        ByteBuffer actual = UnitGenerator.generate(close.asFrame());

        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
            {(byte)0x88, (byte)0x02, 0x03, (byte)0xe8});

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    @Test
    public void testCase733ParseCloseWithStatus()
    {
        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
            {(byte)0x88, (byte)0x02, 0x03, (byte)0xe8});

        expected.flip();

        Parser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        capture.assertHasFrame(OpCode.CLOSE, 1);

        Frame pActual = capture.getFrames().poll();
        assertThat("CloseFrame.payloadLength", pActual.getPayloadLength(), is(2));
    }

    @Test
    public void testCase734GenerateCloseWithStatusReason()
    {
        String message = "bad cough";
        byte[] messageBytes = message.getBytes();

        CloseInfo close = new CloseInfo(1000, message);

        ByteBuffer actual = UnitGenerator.generate(close.asFrame());

        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
            {(byte)0x88});

        byte b = 0x00; // no masking
        b |= (message.length() + 2) & 0x7F;
        expected.put(b);
        expected.putShort((short)1000);
        expected.put(messageBytes);

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    @Test
    public void testCase734ParseCloseWithStatusReason()
    {
        String message = "bad cough";
        byte[] messageBytes = message.getBytes();

        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
            {(byte)0x88});
        byte b = 0x00; // no masking
        b |= (messageBytes.length + 2) & 0x7F;
        expected.put(b);
        expected.putShort((short)1000);
        expected.put(messageBytes);
        expected.flip();

        Parser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        capture.assertHasFrame(OpCode.CLOSE, 1);

        Frame pActual = capture.getFrames().poll();
        assertThat("CloseFrame.payloadLength", pActual.getPayloadLength(), is(messageBytes.length + 2));
    }

    @Test
    public void testCase735GenerateCloseWithStatusMaxReason()
    {
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < 123; ++i)
        {
            message.append("*");
        }

        CloseInfo close = new CloseInfo(1000, message.toString());

        ByteBuffer actual = UnitGenerator.generate(close.asFrame());
        ByteBuffer expected = ByteBuffer.allocate(132);

        byte[] messageBytes = message.toString().getBytes(StandardCharsets.UTF_8);

        expected.put(new byte[]
            {(byte)0x88});

        byte b = 0x00; // no masking
        b |= (messageBytes.length + 2) & 0x7F;
        expected.put(b);
        expected.putShort((short)1000);

        expected.put(messageBytes);

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    @Test
    public void testCase735ParseCloseWithStatusMaxReason()
    {
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < 123; ++i)
        {
            message.append("*");
        }

        byte[] messageBytes = message.toString().getBytes(StandardCharsets.UTF_8);

        ByteBuffer expected = ByteBuffer.allocate(132);

        expected.put(new byte[]
            {(byte)0x88});
        byte b = 0x00; // no masking

        b |= (messageBytes.length + 2) & 0x7F;
        expected.put(b);
        expected.putShort((short)1000);

        expected.put(messageBytes);
        expected.flip();

        Parser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        capture.assertHasFrame(OpCode.CLOSE, 1);

        Frame pActual = capture.getFrames().poll();
        assertThat("CloseFrame.payloadLength", pActual.getPayloadLength(), is(125));
    }

    @Test
    public void testCase736GenerateCloseWithInvalidStatusReason()
    {
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < 124; ++i)
        {
            message.append("*");
        }

        byte[] messageBytes = message.toString().getBytes();

        CloseFrame closeFrame = new CloseFrame();

        ByteBuffer bb = ByteBuffer.allocate(CloseFrame.MAX_CONTROL_PAYLOAD + 1); // 126 which is too big for control

        bb.putChar((char)1000);
        bb.put(messageBytes);

        BufferUtil.flipToFlush(bb, 0);

        assertThrows(ProtocolException.class, () ->
        {
            closeFrame.setPayload(bb);
            UnitGenerator.generate(closeFrame);
        });
    }

    @Test
    public void testCase736ParseCloseWithInvalidStatusReason()
    {
        byte[] messageBytes = new byte[124];
        Arrays.fill(messageBytes, (byte)'*');

        ByteBuffer expected = ByteBuffer.allocate(256);

        byte b;

        // fin + op
        b = 0x00;
        b |= 0x80; // fin on
        b |= 0x08; // close
        expected.put(b);

        // mask + len
        b = 0x00;
        b |= 0x00; // no masking
        b |= 0x7E; // 2 byte len
        expected.put(b);

        // 2 byte len
        expected.putChar((char)(messageBytes.length + 2));

        // payload
        expected.putShort((short)1000); // status code
        expected.put(messageBytes); // reason

        expected.flip();

        UnitParser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        assertThrows(ProtocolException.class, () -> parser.parseQuietly(expected));
    }
}
