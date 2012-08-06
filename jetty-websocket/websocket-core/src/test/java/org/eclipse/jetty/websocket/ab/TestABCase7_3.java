// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.ab;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.ByteBufferAssert;
import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.protocol.CloseInfo;
import org.eclipse.jetty.websocket.protocol.Generator;
import org.eclipse.jetty.websocket.protocol.IncomingFramesCapture;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.Parser;
import org.eclipse.jetty.websocket.protocol.UnitGenerator;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.junit.Assert;
import org.junit.Test;

public class TestABCase7_3
{
    WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);

    @Test
    public void testCase7_3_1GenerateEmptyClose()
    {
        CloseInfo close = new CloseInfo();

        Generator generator = new UnitGenerator();
        ByteBuffer actual = generator.generate(close.asFrame());

        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
                { (byte)0x88, (byte)0x00 });

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    @Test
    public void testCase7_3_1ParseEmptyClose()
    {
        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
                { (byte)0x88, (byte)0x00 });

        expected.flip();

        Parser parser = new Parser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.CLOSE,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("CloseFrame.payloadLength",pActual.getPayloadLength(),is(0));

    }


    @Test(expected = ProtocolException.class)
    public void testCase7_3_2Generate1BytePayloadClose()
    {
        WebSocketFrame closeFrame = new WebSocketFrame(OpCode.CLOSE).setPayload(new byte[]
                { 0x00 });

        Generator generator = new UnitGenerator();
        generator.generate(closeFrame);
    }

    @Test
    public void testCase7_3_2Parse1BytePayloadClose()
    {
        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
                { (byte)0x88, 0x01, 0x00 });

        expected.flip();

        Parser parser = new Parser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        Assert.assertEquals("error on invalid close payload",1,capture.getErrorCount(ProtocolException.class));

        ProtocolException known = (ProtocolException)capture.getErrors().get(0);

        Assert.assertThat("Payload.message",known.getMessage(),containsString("Invalid close frame payload length"));
    }

    @Test
    public void testCase7_3_3GenerateCloseWithStatus()
    {
        CloseInfo close = new CloseInfo(1000);

        Generator generator = new UnitGenerator();
        ByteBuffer actual = generator.generate(close.asFrame());

        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
                { (byte)0x88, (byte)0x02, 0x03, (byte)0xe8 });

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    @Test
    public void testCase7_3_3ParseCloseWithStatus()
    {
        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
                { (byte)0x88, (byte)0x02, 0x03, (byte)0xe8  });

        expected.flip();

        Parser parser = new Parser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.CLOSE,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("CloseFrame.payloadLength",pActual.getPayloadLength(),is(2));

    }


    @Test
    public void testCase7_3_4GenerateCloseWithStatusReason()
    {
        String message = "bad cough";
        byte[] messageBytes = message.getBytes();

        CloseInfo close = new CloseInfo(1000,message);

        Generator generator = new UnitGenerator();
        ByteBuffer actual = generator.generate(close.asFrame());

        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
                { (byte)0x88 });

        byte b = 0x00; // no masking
        b |= (message.length() + 2) & 0x7F;
        expected.put(b);
        expected.putShort((short)1000);
        expected.put(messageBytes);

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    @Test
    public void testCase7_3_4ParseCloseWithStatusReason()
    {
        String message = "bad cough";
        byte[] messageBytes = message.getBytes();

        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
                { (byte)0x88 });
        byte b = 0x00; // no masking
        b |= (messageBytes.length + 2) & 0x7F;
        expected.put(b);
        expected.putShort((short)1000);
        expected.put(messageBytes);
        expected.flip();

        Parser parser = new Parser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.CLOSE,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("CloseFrame.payloadLength",pActual.getPayloadLength(),is(messageBytes.length + 2));

    }


    @Test
    public void testCase7_3_5GenerateCloseWithStatusMaxReason()
    {
        StringBuilder message = new StringBuilder();
        for ( int i = 0 ; i < 123 ; ++i )
        {
            message.append("*");
        }

        CloseInfo close = new CloseInfo(1000,message.toString());

        Generator generator = new UnitGenerator();
        ByteBuffer actual = generator.generate(close.asFrame());
        ByteBuffer expected = ByteBuffer.allocate(132);

        byte messageBytes[] = message.toString().getBytes(StringUtil.__UTF8_CHARSET);

        expected.put(new byte[]
                { (byte)0x88 });

        byte b = 0x00; // no masking
        b |= (messageBytes.length + 2) & 0x7F;
        expected.put(b);
        expected.putShort((short)1000);

        expected.put(messageBytes);

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    @Test
    public void testCase7_3_5ParseCloseWithStatusMaxReason()
    {
        StringBuilder message = new StringBuilder();
        for ( int i = 0 ; i < 123 ; ++i )
        {
            message.append("*");
        }

        byte[] messageBytes = message.toString().getBytes(StringUtil.__UTF8_CHARSET);

        ByteBuffer expected = ByteBuffer.allocate(132);

        expected.put(new byte[]
                { (byte)0x88 });
        byte b = 0x00; // no masking

        b |= (messageBytes.length + 2) & 0x7F;
        expected.put(b);
        expected.putShort((short)1000);

        expected.put(messageBytes);
        expected.flip();

        Parser parser = new Parser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.CLOSE,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("CloseFrame.payloadLength",pActual.getPayloadLength(),is(125));

    }

    @Test(expected = ProtocolException.class)
    public void testCase7_3_6GenerateCloseWithInvalidStatusReason()
    {
        StringBuilder message = new StringBuilder();
        for ( int i = 0 ; i < 124 ; ++i )
        {
            message.append("*");
        }

        byte[] messageBytes = message.toString().getBytes();

        WebSocketFrame closeFrame = new WebSocketFrame(OpCode.CLOSE);

        ByteBuffer bb = ByteBuffer.allocate(WebSocketFrame.MAX_CONTROL_PAYLOAD + 1); // 126 which is too big for control

        bb.putChar((char)1000);
        bb.put(messageBytes);

        BufferUtil.flipToFlush(bb,0);

        closeFrame.setPayload(BufferUtil.toArray(bb));

        Generator generator = new UnitGenerator();
        generator.generate(closeFrame);
    }

    @Test
    public void testCase7_3_6ParseCloseWithInvalidStatusReason()
    {
        byte[] messageBytes = new byte[124];
        Arrays.fill(messageBytes,(byte)'*');

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

        Parser parser = new Parser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        Assert.assertEquals("error on invalid close payload",1,capture.getErrorCount(ProtocolException.class));

        ProtocolException known = (ProtocolException)capture.getErrors().get(0);

        Assert.assertThat("Payload.message",known.getMessage(),containsString("Invalid control frame payload length"));
    }
}
