//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.ab;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.ByteBufferAssert;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.protocol.Generator;
import org.eclipse.jetty.websocket.protocol.IncomingFramesCapture;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.Parser;
import org.eclipse.jetty.websocket.protocol.UnitGenerator;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;
import org.junit.Assert;
import org.junit.Test;

import static org.hamcrest.Matchers.is;

/**
 * Text Message Spec testing the {@link Generator} and {@link Parser}
 */
public class TestABCase1_1
{
    private WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();

    @Test
    public void testGenerate125ByteTextCase1_1_2()
    {
        int length = 125;

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }

        WebSocketFrame textFrame = WebSocketFrame.text(builder.toString());

        Generator generator = new UnitGenerator();
        ByteBuffer actual = generator.generate(textFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });

        byte b = 0x00; // no masking
        b |= length & 0x7F;
        expected.put(b);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    @Test
    public void testGenerate126ByteTextCase1_1_3()
    {
        int length = 126;

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }

        WebSocketFrame textFrame = WebSocketFrame.text(builder.toString());

        Generator generator = new UnitGenerator();
        ByteBuffer actual = generator.generate(textFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });

        byte b = 0x00; // no masking
        b |= length & 0x7E;
        expected.put(b);

        // expected.put((byte)((length>>8) & 0xFF));
        // expected.put((byte)(length & 0xFF));
        expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    @Test
    public void testGenerate127ByteTextCase1_1_4()
    {
        int length = 127;

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }

        WebSocketFrame textFrame = WebSocketFrame.text(builder.toString());

        Generator generator = new UnitGenerator();
        ByteBuffer actual = generator.generate(textFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });

        byte b = 0x00; // no masking
        b |= length & 0x7E;
        expected.put(b);

        // expected.put((byte)((length>>8) & 0xFF));
        // expected.put((byte)(length & 0xFF));
        expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    @Test
    public void testGenerate128ByteTextCase1_1_5()
    {
        int length = 128;

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }

        WebSocketFrame textFrame = WebSocketFrame.text(builder.toString());

        Generator generator = new UnitGenerator();
        ByteBuffer actual = generator.generate(textFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });

        byte b = 0x00; // no masking
        b |= 0x7E;
        expected.put(b);

        expected.put((byte)(length >> 8));
        expected.put((byte)(length & 0xFF));
        // expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    @Test
    public void testGenerate65535ByteTextCase1_1_6()
    {
        int length = 65535;

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }

        WebSocketFrame textFrame = WebSocketFrame.text(builder.toString());

        Generator generator = new UnitGenerator();

        ByteBuffer actual = generator.generate(textFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });

        byte b = 0x00; // no masking
        b |= 0x7E;
        expected.put(b);
        expected.put(new byte[]
                { (byte)0xff, (byte)0xff });

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    @Test
    public void testGenerate65536ByteTextCase1_1_7()
    {
        int length = 65536;

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }

        WebSocketFrame textFrame = WebSocketFrame.text(builder.toString());

        Generator generator = new UnitGenerator();
        ByteBuffer actual = generator.generate(textFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 11);

        expected.put(new byte[]
                { (byte)0x81 });

        byte b = 0x00; // no masking
        b |= 0x7F;
        expected.put(b);
        expected.put(new byte[]
                { 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00 });

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    @Test
    public void testGenerateEmptyTextCase1_1_1()
    {
        WebSocketFrame textFrame = WebSocketFrame.text("");

        Generator generator = new UnitGenerator();
        ByteBuffer actual = generator.generate(textFrame);

        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
                { (byte)0x81, (byte)0x00 });

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    @Test
    public void testParse125ByteTextCase1_1_2()
    {
        int length = 125;

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });
        byte b = 0x00; // no masking
        b |= length & 0x7F;
        expected.put(b);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();

        Parser parser = new Parser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.TEXT,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(length));
        // Assert.assertEquals("TextFrame.payload",length,pActual.getPayloadData().length);
    }

    @Test
    public void testParse126ByteTextCase1_1_3()
    {
        int length = 126;

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });
        byte b = 0x00; // no masking
        b |= length & 0x7E;
        expected.put(b);
        expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();

        Parser parser = new Parser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.TEXT,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(length));
        // Assert.assertEquals("TextFrame.payload",length,pActual.getPayloadData().length);
    }

    @Test
    public void testParse127ByteTextCase1_1_4()
    {
        int length = 127;

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });
        byte b = 0x00; // no masking
        b |= length & 0x7E;
        expected.put(b);
        expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();

        Parser parser = new Parser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.TEXT,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(length));
        // Assert.assertEquals("TextFrame.payload",length,pActual.getPayloadData().length);
    }

    @Test
    public void testParse128ByteTextCase1_1_5()
    {
        int length = 128;

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });
        byte b = 0x00; // no masking
        b |= 0x7E;
        expected.put(b);
        expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();

        Parser parser = new Parser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.TEXT,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(length));
        // .assertEquals("TextFrame.payload",length,pActual.getPayloadData().length);
    }

    @Test
    public void testParse65535ByteTextCase1_1_6()
    {
        // Debug.enableDebugLogging(Parser.class);
        // Debug.enableDebugLogging(TextPayloadParser.class);

        int length = 65535;

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
                { (byte)0x81 });
        byte b = 0x00; // no masking
        b |= 0x7E;
        expected.put(b);
        expected.put(new byte[]
                { (byte)0xff, (byte)0xff });

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        policy.setMaxTextMessageSize(length);
        Parser parser = new Parser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.TEXT,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(length));
        // Assert.assertEquals("TextFrame.payload",length,pActual.getPayloadData().length);
    }

    @Test
    public void testParse65536ByteTextCase1_1_7()
    {
        int length = 65536;

        ByteBuffer expected = ByteBuffer.allocate(length + 11);

        expected.put(new byte[]
                { (byte)0x81 });
        byte b = 0x00; // no masking
        b |= 0x7F;
        expected.put(b);
        expected.put(new byte[]
                { 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00 });

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        policy.setMaxTextMessageSize(length);
        Parser parser = new Parser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.TEXT,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(length));
        // Assert.assertEquals("TextFrame.payload",length,pActual.getPayloadData().length);
    }

    @Test
    public void testParseEmptyTextCase1_1_1()
    {

        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
                { (byte)0x81, (byte)0x00 });

        expected.flip();

        Parser parser = new Parser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(expected);

        capture.assertNoErrors();
        capture.assertHasFrame(OpCode.TEXT,1);

        WebSocketFrame pActual = capture.getFrames().get(0);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(0));
    }
}
