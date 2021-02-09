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

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.test.IncomingFramesCapture;
import org.eclipse.jetty.websocket.common.test.UnitParser;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Collection of Example packets as found in <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
 */
public class RFC6455ExamplesParserTest
{
    @Test
    public void testFragmentedUnmaskedTextMessage()
    {
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        Parser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);

        ByteBuffer buf = ByteBuffer.allocate(16);
        BufferUtil.clearToFill(buf);

        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // A fragmented unmasked text message (part 1 of 2 "Hel")
        buf.put(new byte[]
            {(byte)0x01, (byte)0x03, 0x48, (byte)0x65, 0x6c});

        // Parse #1
        BufferUtil.flipToFlush(buf, 0);
        parser.parse(buf);

        // part 2 of 2 "lo" (A continuation frame of the prior text message)
        BufferUtil.flipToFill(buf);
        buf.put(new byte[]
            {(byte)0x80, 0x02, 0x6c, 0x6f});

        // Parse #2
        BufferUtil.flipToFlush(buf, 0);
        parser.parse(buf);

        capture.assertHasFrame(OpCode.TEXT, 1);
        capture.assertHasFrame(OpCode.CONTINUATION, 1);

        WebSocketFrame txt = capture.getFrames().poll();
        String actual = BufferUtil.toUTF8String(txt.getPayload());
        assertThat("TextFrame[0].data", actual, is("Hel"));
        txt = capture.getFrames().poll();
        actual = BufferUtil.toUTF8String(txt.getPayload());
        assertThat("TextFrame[1].data", actual, is("lo"));
    }

    @Test
    public void testSingleMaskedPongRequest()
    {
        ByteBuffer buf = ByteBuffer.allocate(16);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // Unmasked Pong request
        buf.put(new byte[]
            {(byte)0x8a, (byte)0x85, 0x37, (byte)0xfa, 0x21, 0x3d, 0x7f, (byte)0x9f, 0x4d, 0x51, 0x58});
        buf.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        Parser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(buf);

        capture.assertHasFrame(OpCode.PONG, 1);

        WebSocketFrame pong = capture.getFrames().poll();
        String actual = BufferUtil.toUTF8String(pong.getPayload());
        assertThat("PongFrame.payload", actual, is("Hello"));
    }

    @Test
    public void testSingleMaskedTextMessage()
    {
        ByteBuffer buf = ByteBuffer.allocate(16);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // A single-frame masked text message
        buf.put(new byte[]
            {(byte)0x81, (byte)0x85, 0x37, (byte)0xfa, 0x21, 0x3d, 0x7f, (byte)0x9f, 0x4d, 0x51, 0x58});
        buf.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        Parser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(buf);

        capture.assertHasFrame(OpCode.TEXT, 1);

        WebSocketFrame txt = capture.getFrames().poll();
        String actual = BufferUtil.toUTF8String(txt.getPayload());
        assertThat("TextFrame.payload", actual, is("Hello"));
    }

    @Test
    public void testSingleUnmasked256ByteBinaryMessage()
    {
        int dataSize = 256;

        ByteBuffer buf = ByteBuffer.allocate(dataSize + 10);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // 256 bytes binary message in a single unmasked frame
        buf.put(new byte[]
            {(byte)0x82, 0x7E});
        buf.putShort((short)0x01_00); // 16 bit size
        for (int i = 0; i < dataSize; i++)
        {
            buf.put((byte)0x44);
        }
        buf.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        Parser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(buf);

        capture.assertHasFrame(OpCode.BINARY, 1);

        Frame bin = capture.getFrames().poll();

        assertThat("BinaryFrame.payloadLength", bin.getPayloadLength(), is(dataSize));

        ByteBuffer data = bin.getPayload();
        assertThat("BinaryFrame.payload.length", data.remaining(), is(dataSize));

        for (int i = 0; i < dataSize; i++)
        {
            assertThat("BinaryFrame.payload[" + i + "]", data.get(i), is((byte)0x44));
        }
    }

    @Test
    public void testSingleUnmasked64KByteBinaryMessage()
    {
        int dataSize = 1024 * 64;

        ByteBuffer buf = ByteBuffer.allocate((dataSize + 10));
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // 64 Kbytes binary message in a single unmasked frame
        buf.put(new byte[]
            {(byte)0x82, 0x7F});
        buf.putLong(dataSize); // 64bit size
        for (int i = 0; i < dataSize; i++)
        {
            buf.put((byte)0x77);
        }
        buf.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        Parser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(buf);

        capture.assertHasFrame(OpCode.BINARY, 1);

        Frame bin = capture.getFrames().poll();

        assertThat("BinaryFrame.payloadLength", bin.getPayloadLength(), is(dataSize));
        ByteBuffer data = bin.getPayload();
        assertThat("BinaryFrame.payload.length", data.remaining(), is(dataSize));

        for (int i = 0; i < dataSize; i++)
        {
            assertThat("BinaryFrame.payload[" + i + "]", data.get(i), is((byte)0x77));
        }
    }

    @Test
    public void testSingleUnmaskedPingRequest()
    {
        ByteBuffer buf = ByteBuffer.allocate(16);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // Unmasked Ping request
        buf.put(new byte[]
            {(byte)0x89, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f});
        buf.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        Parser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(buf);

        capture.assertHasFrame(OpCode.PING, 1);

        WebSocketFrame ping = capture.getFrames().poll();
        String actual = BufferUtil.toUTF8String(ping.getPayload());
        assertThat("PingFrame.payload", actual, is("Hello"));
    }

    @Test
    public void testSingleUnmaskedTextMessage()
    {
        ByteBuffer buf = ByteBuffer.allocate(16);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // A single-frame unmasked text message
        buf.put(new byte[]
            {(byte)0x81, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f});
        buf.flip();

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        Parser parser = new UnitParser(policy);
        IncomingFramesCapture capture = new IncomingFramesCapture();
        parser.setIncomingFramesHandler(capture);
        parser.parse(buf);

        capture.assertHasFrame(OpCode.TEXT, 1);

        WebSocketFrame txt = capture.getFrames().poll();
        String actual = BufferUtil.toUTF8String(txt.getPayload());
        assertThat("TextFrame.payload", actual, is("Hello"));
    }
}
