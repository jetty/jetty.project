//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.websocket.core.exception.MessageTooLargeException;
import org.eclipse.jetty.websocket.core.exception.ProtocolException;
import org.eclipse.jetty.websocket.core.internal.Generator;
import org.eclipse.jetty.websocket.core.internal.ParsedFrame;
import org.eclipse.jetty.websocket.core.internal.Parser;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ParserTest
{
    private static final int MAX_ALLOWED_FRAME_SIZE = 4 * 1024 * 1024;
    private static final byte[] mask = {0x00, (byte)0xF0, 0x0F, (byte)0xFF};

    public static byte[] maskPayload(byte[] payload)
    {
        int len = payload.length;
        byte[] masked = new byte[len];
        for (int i = 0; i < len; i++)
        {
            masked[i] = (byte)(payload[i] ^ mask[i % 4]);
        }
        return masked;
    }

    public static void putPayload(RetainableByteBuffer.Mutable buffer, byte[] payload)
    {
        buffer.put(maskPayload(payload));
    }

    private ParserCapture parse(Behavior behavior, int maxAllowedFrameSize, RetainableByteBuffer buffer)
    {
        ParserCapture capture = new ParserCapture(behavior);
        capture.getCoreSession().setMaxFrameSize(maxAllowedFrameSize);
        capture.parse(buffer);
        return capture;
    }

    ByteBuffer generate(byte opcode, String payload)
    {
        return generate(opcode, payload, false);
    }

    ByteBuffer generate(byte opcode, String payload, boolean masked)
    {
        byte[] messageBytes = payload.getBytes(StandardCharsets.UTF_8);
        if (messageBytes.length > 125)
            throw new IllegalArgumentException();

        ByteBuffer buffer = ByteBuffer.allocate(messageBytes.length + 8);

        buffer.put((byte)(0x80 | opcode));
        byte b = (byte)(masked ? 0x80 : 0x00);
        b |= messageBytes.length & 0x7F;
        buffer.put(b);
        if (masked)
        {
            buffer.put(mask);
            buffer.put(maskPayload(messageBytes));
        }
        else
        {
            buffer.put(messageBytes);
        }
        buffer.flip();
        return buffer;
    }

    /**
     * From Autobahn WebSocket Server Testcase 1.2.2
     */
    @Test
    public void testParseBinary125BytePayload() throws InterruptedException
    {
        int length = 125;

        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(length + 5, false);

        expected.put(new byte[]{(byte)0x82});
        byte b = 0x00; // no masking
        b |= length & 0x7F;
        expected.put(b);

        for (int i = 0; i < length; ++i)
        {
            expected.put((byte)'*');
        }

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertThat("Frame", pActual.getOpCode(), is(OpCode.BINARY));
            assertEquals(length, pActual.getPayloadLength(), "Frame.payloadLength");
        }
    }

    /**
     * From Autobahn WebSocket Server Testcase 1.2.3
     */
    @Test
    public void testParseBinary126BytePayload() throws InterruptedException
    {
        int length = 126;

        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(length + 5, false);

        expected.put(new byte[]
            {(byte)0x82});
        byte b = 0x00; // no masking
        b |= length & 0x7E;
        expected.put(b);
        expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put((byte)'*');
        }

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        capture.assertHasFrame(OpCode.BINARY, 1);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertEquals(length, pActual.getPayloadLength(), "Frame.payloadLength");
        }
        // assertEquals("Frame.payload",length,pActual.getPayloadData().length);
    }

    /**
     * From Autobahn WebSocket Server Testcase 1.2.4
     */
    @Test
    public void testParseBinary127BytePayload() throws InterruptedException
    {
        int length = 127;

        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(length + 5, false);

        expected.put(new byte[]
            {(byte)0x82});
        byte b = 0x00; // no masking
        b |= length & 0x7E;
        expected.put(b);
        expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put((byte)'*');
        }

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertThat("Frame", pActual.getOpCode(), is(OpCode.BINARY));
            assertEquals(length, pActual.getPayloadLength(), "Frame.payloadLength");
        }
        // .assertEquals("Frame.payload",length,pActual.getPayloadData().length);
    }

    /**
     * From Autobahn WebSocket Server Testcase 1.2.5
     */
    @Test
    public void testParseBinary128BytePayload() throws InterruptedException
    {
        int length = 128;

        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(length + 5, false);

        expected.put(new byte[]
            {(byte)0x82});
        byte b = 0x00; // no masking
        b |= 0x7E;
        expected.put(b);
        expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put((byte)'*');
        }

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        capture.assertHasFrame(OpCode.BINARY, 1);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertEquals(length, pActual.getPayloadLength(), "Frame.payloadLength");
        }
    }

    private ByteBuffer toBuffer(long l)
    {
        ByteBuffer buffer = BufferUtil.allocate(Long.BYTES);
        BufferUtil.clearToFill(buffer);
        buffer.putLong(l);
        BufferUtil.flipToFlush(buffer, 0);
        return buffer;
    }

    @Test
    public void testLargeFrame()
    {
        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(65, false);

        expected.put(new byte[]{(byte)0x82});
        byte b = 0x7F; // no masking
        expected.put(b);
        expected.put(toBuffer(Integer.MAX_VALUE));

        Parser parser = new Parser(ByteBufferPool.NON_POOLING);
        assertNull(parser.parse(expected));
        assertThat(parser.getPayloadLength(), equalTo((long)Integer.MAX_VALUE));
    }

    @Test
    public void testFrameTooLarge()
    {
        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(65, false);

        expected.put(new byte[]{(byte)0x82});
        byte b = 0x7F; // no masking
        expected.put(b);
        expected.put(toBuffer(Integer.MAX_VALUE + 1L));

        Parser parser = new Parser(ByteBufferPool.NON_POOLING);
        assertThrows(MessageTooLargeException.class, () -> parser.parse(expected));
    }

    @Test
    public void testLargestFrame()
    {
        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(65, false);

        expected.put(new byte[]{(byte)0x82});
        byte b = 0x7F; // no masking
        expected.put(b);
        expected.put(new byte[]{(byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF, (byte)0xFF});

        Parser parser = new Parser(ByteBufferPool.NON_POOLING);
        assertThrows(MessageTooLargeException.class, () -> parser.parse(expected));
    }

    /**
     * From Autobahn WebSocket Server Testcase 1.2.6
     */
    @Test
    public void testParseBinary65535BytePayload() throws InterruptedException
    {
        int length = 65535;

        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(length + 5, false);

        expected.put(new byte[]
            {(byte)0x82});
        byte b = 0x00; // no masking
        b |= 0x7E;
        expected.put(b);
        expected.put(new byte[]{(byte)0xff, (byte)0xff});

        for (int i = 0; i < length; ++i)
        {
            expected.put((byte)'*');
        }

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        capture.assertHasFrame(OpCode.BINARY, 1);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertEquals(length, pActual.getPayloadLength(), "Frame.payloadLength");
        }
    }

    /**
     * From Autobahn WebSocket Server Testcase 1.2.7
     */
    @Test
    public void testParseBinary65536BytePayload() throws InterruptedException
    {
        int length = 65536;

        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(length + 11, false);

        expected.put(new byte[]
            {(byte)0x82});
        byte b = 0x00; // no masking
        b |= 0x7F;
        expected.put(b);
        expected.put(new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00});

        for (int i = 0; i < length; ++i)
        {
            expected.put((byte)'*');
        }

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        capture.assertHasFrame(OpCode.BINARY, 1);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertEquals(length, pActual.getPayloadLength(), "Frame.payloadLength");
        }
    }

    /**
     * From Autobahn WebSocket Server Testcase 1.2.1
     */
    @Test
    public void testParseBinaryEmpty() throws InterruptedException
    {
        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(5, false);

        expected.put(new byte[]{(byte)0x82, (byte)0x00});

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        capture.assertHasFrame(OpCode.BINARY, 1);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertThat("Frame.payloadLength", pActual.getPayloadLength(), is(0L));
        }
    }

    /**
     * From Autobahn WebSocket Server Testcase 7.3.2
     */
    @Test
    public void testParseClose1BytePayload()
    {
        RetainableByteBuffer expected = RetainableByteBuffer.wrap(Hex.asByteBuffer("880100"));

        Exception e = assertThrows(ProtocolException.class, () -> parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected));
        assertThat(e.getMessage(), Matchers.containsString("Invalid CLOSE payload"));
    }

    /**
     * From Autobahn WebSocket Server Testcase 7.3.1
     */
    @Test
    public void testParseCloseEmpty() throws InterruptedException
    {
        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(5, false);

        expected.put(new byte[]
            {(byte)0x88, (byte)0x00});

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        capture.assertHasFrame(OpCode.CLOSE, 1);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertThat("CloseFrame.payloadLength", pActual.getPayloadLength(), is(0L));
        }
    }

    /**
     * From Autobahn WebSocket Server Testcase 7.4.6
     */
    @Test
    public void testParseCloseWithInvalidStatusReason()
    {
        byte[] messageBytes = new byte[124];
        Arrays.fill(messageBytes, (byte)'*');

        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(256, false);

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
        expected.putShort((short)(messageBytes.length + 2));

        // payload
        expected.putShort((short)1000); // status code
        expected.put(messageBytes); // reason

        Exception e = assertThrows(ProtocolException.class, () -> parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected));
        assertThat(e.getMessage(), Matchers.containsString("Invalid control frame payload length"));
    }

    /**
     * From Autobahn WebSocket Server Testcase 7.3.3
     */
    @Test
    public void testParseCloseWithStatus() throws InterruptedException
    {
        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(5, false);

        expected.put(new byte[]
            {(byte)0x88, (byte)0x02, 0x03, (byte)0xe8});

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        capture.assertHasFrame(OpCode.CLOSE, 1);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertThat("CloseFrame.payloadLength", pActual.getPayloadLength(), is(2L));
        }
    }

    /**
     * From Autobahn WebSocket Server Testcase 7.3.5
     */
    @Test
    public void testParseCloseWithStatusMaxReason() throws InterruptedException
    {
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < 123; ++i)
        {
            message.append("*");
        }

        byte[] messageBytes = message.toString().getBytes(StandardCharsets.UTF_8);

        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(132, false);

        expected.put(new byte[]
            {(byte)0x88});
        byte b = 0x00; // no masking

        b |= (messageBytes.length + 2) & 0x7F;
        expected.put(b);
        expected.putShort((short)1000);

        expected.put(messageBytes);

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        capture.assertHasFrame(OpCode.CLOSE, 1);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertThat("CloseFrame.payloadLength", pActual.getPayloadLength(), is(125L));
        }
    }

    /**
     * From Autobahn WebSocket Server Testcase 7.3.4
     */
    @Test
    public void testParseCloseWithStatusReason() throws InterruptedException
    {
        String message = "bad cough";
        byte[] messageBytes = message.getBytes();

        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(32, false);

        expected.put(new byte[]
            {(byte)0x88});
        byte b = 0x00; // no masking
        b |= (messageBytes.length + 2) & 0x7F;
        expected.put(b);
        expected.putShort((short)1000); // status code
        expected.put(messageBytes); // status reason

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        capture.assertHasFrame(OpCode.CLOSE, 1);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertEquals(messageBytes.length + 2, pActual.getPayloadLength(), "CloseFrame.payloadLength");
        }
    }

    /**
     * From Autobahn WebSocket Server Testcase 6.2.3
     * <p>
     * Lots of small 1 byte UTF8 Text frames, representing 1 overall text message.
     * </p>
     */
    @Test
    public void testParseContinuationManySmall()
    {
        // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        String utf8 = "Hello-\uC2B5@\uC39F\uC3A4\uC3BC\uC3A0\uC3A1-UTF-8!!";
        byte[] msg = StringUtil.getUtf8Bytes(utf8);

        List<Frame> send = new ArrayList<>();
        int textCount = 0;
        int continuationCount = 0;
        int len = msg.length;
        byte[] mini;
        for (int i = 0; i < len; i++)
        {
            Frame frame;
            byte opCode;
            if (i > 0)
            {
                opCode = OpCode.CONTINUATION;
                continuationCount++;
            }
            else
            {
                opCode = OpCode.TEXT;
                textCount++;
            }
            mini = new byte[1];
            mini[0] = msg[i];
            boolean isLast = (i >= (len - 1));
            frame = new Frame(opCode, isLast, RetainableByteBuffer.wrap(mini));
            send.add(frame);
        }
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        RetainableByteBuffer completeBuf = generate(Behavior.SERVER, send);

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, completeBuf);

        capture.assertHasFrame(OpCode.TEXT, textCount);
        capture.assertHasFrame(OpCode.CONTINUATION, continuationCount);
        capture.assertHasFrame(OpCode.CLOSE, 1);
    }

    /**
     * From Autobahn WebSocket Server Testcase 5.19
     * <p>
     * text message, send in 5 frames/fragments, with 2 pings in the mix.
     * </p>
     */
    @Test
    public void testParseInterleavedPingFrames()
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.TEXT, false, "f1"));
        send.add(new Frame(OpCode.CONTINUATION, false, ",f2"));
        send.add(new Frame(OpCode.PING, "ping-1"));
        send.add(new Frame(OpCode.CONTINUATION, false, ",f3"));
        send.add(new Frame(OpCode.CONTINUATION, false, ",f4"));
        send.add(new Frame(OpCode.PING, "ping-2"));
        send.add(new Frame(OpCode.CONTINUATION, true, ",f5"));
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        RetainableByteBuffer completeBuf = generate(Behavior.SERVER, send);

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, completeBuf);

        capture.assertHasFrame(OpCode.TEXT, 1);
        capture.assertHasFrame(OpCode.CONTINUATION, 4);
        capture.assertHasFrame(OpCode.CLOSE, 1);
        capture.assertHasFrame(OpCode.PING, 2);
    }

    @Test
    public void testParseNothing()
    {
        RetainableByteBuffer.Mutable buf = RetainableByteBuffer.Mutable.allocate(16, false);
        // Put nothing in the buffer.

        ParserCapture capture = parse(Behavior.SERVER, MAX_ALLOWED_FRAME_SIZE, buf);

        assertThat("Frame Count", capture.framesQueue.size(), is(0));
    }

    /**
     * From Autobahn WebSocket Server Testcase 4.2.1
     */
    @Test
    public void testParseOpCode11() throws Exception
    {
        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(32, false);

        expected.put(new byte[]{(byte)0x8b, 0x00});

        Exception e = assertThrows(ProtocolException.class, () -> parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected));
        assertThat(e.getMessage(), Matchers.containsString("Unknown opcode: 11"));
    }

    /**
     * From Autobahn WebSocket Server Testcase 4.2.2
     */
    @Test
    public void testParseOpCode12() throws Exception
    {
        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(32, false);

        expected.put(new byte[]{(byte)0x8c, 0x01, 0x00});

        Exception e = assertThrows(ProtocolException.class, () -> parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected));
        assertThat(e.getMessage(), Matchers.containsString("Unknown opcode: 12"));
    }

    /**
     * From Autobahn WebSocket Server Testcase 4.1.1
     */
    @Test
    public void testParseOpCode3() throws Exception
    {
        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(32, false);

        expected.put(new byte[]{(byte)0x83, 0x00});

        Exception e = assertThrows(ProtocolException.class, () -> parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected));
        assertThat(e.getMessage(), Matchers.containsString("Unknown opcode: 3"));
    }

    /**
     * From Autobahn WebSocket Server Testcase 4.1.2
     */
    @Test
    public void testParseOpCode4() throws Exception
    {
        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(32, false);

        expected.put(new byte[]{(byte)0x84, 0x01, 0x00});

        Exception e = assertThrows(ProtocolException.class, () -> parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected));
        assertThat(e.getMessage(), Matchers.containsString("Unknown opcode: 4"));
    }

    /**
     * From Autobahn WebSocket Server Testcase 2.4
     */
    @Test
    public void testParsePing125BytePayload() throws InterruptedException
    {
        byte[] bytes = new byte[125];

        for (int i = 0; i < bytes.length; ++i)
        {
            bytes[i] = (byte)(i & 0xff);
        }

        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(bytes.length + 32, false);

        expected.put(new byte[]
            {(byte)0x89});

        byte b = 0x00; // no masking
        b |= bytes.length & 0x7F;
        expected.put(b);
        expected.put(bytes);

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        capture.assertHasFrame(OpCode.PING, 1);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertEquals(bytes.length, pActual.getPayloadLength(), "PingFrame.payloadLength");
            assertEquals(bytes.length, pActual.getPayloadLength(), "PingFrame.payload");
        }
    }

    @Test
    public void testParsePingBasic() throws InterruptedException
    {
        RetainableByteBuffer.Mutable buf = RetainableByteBuffer.Mutable.allocate(16, false);
        buf.put(new byte[]
            {(byte)0x89, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f});

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, buf);

        capture.assertHasFrame(OpCode.PING, 1);
        try (Frame ping = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertThat("PingFrame", ping.getOpCode(), is(OpCode.PING));
            String actual = ping.getPayloadAsUTF8();
            assertThat("PingFrame.payload", actual, is("Hello"));
        }
    }

    /**
     * From Autobahn WebSocket Server Testcase 2.3
     */
    @Test
    public void testParsePingBinaryPayload() throws InterruptedException
    {
        byte[] bytes = new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};

        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(32, false);

        expected.put(new byte[]
            {(byte)0x89});

        byte b = 0x00; // no masking
        b |= bytes.length & 0x7F;
        expected.put(b);
        expected.put(bytes);

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        capture.assertHasFrame(OpCode.PING, 1);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertEquals(bytes.length, pActual.getPayloadLength(), "PingFrame.payloadLength");
            assertEquals(bytes.length, pActual.getPayloadLength(), "PingFrame.payload");
        }
    }

    /**
     * From Autobahn WebSocket Server Testcase 2.1
     */
    @Test
    public void testParsePingEmpty() throws InterruptedException
    {
        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(5, false);

        expected.put(new byte[]
            {(byte)0x89, (byte)0x00});

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        capture.assertHasFrame(OpCode.PING, 1);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertThat("PingFrame.payloadLength", pActual.getPayloadLength(), is(0L));
            assertEquals(0, pActual.getPayloadLength(), "PingFrame.payload");
        }
    }

    /**
     * From Autobahn WebSocket Server Testcase 2.2
     */
    @Test
    public void testParsePingHelloPayload() throws InterruptedException
    {
        String message = "Hello, world!";
        byte[] messageBytes = message.getBytes();

        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(32, false);

        expected.put(new byte[]
            {(byte)0x89});

        byte b = 0x00; // no masking
        b |= messageBytes.length & 0x7F;
        expected.put(b);
        expected.put(messageBytes);

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        capture.assertHasFrame(OpCode.PING, 1);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertEquals(message.length(), pActual.getPayloadLength(), "PingFrame.payloadLength");
            assertEquals(message.length(), pActual.getPayloadLength(), "PingFrame.payload");
        }
    }

    /**
     * From Autobahn WebSocket Server Testcase 2.5
     */
    @Test
    public void testParsePingOverSizedPayload()
    {
        byte[] bytes = new byte[126];
        Arrays.fill(bytes, (byte)0x00);

        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(bytes.length + Generator.MAX_HEADER_LENGTH, false);

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
        expected.putShort((short)bytes.length);

        // payload
        expected.put(bytes);

        assertThrows(ProtocolException.class, () -> parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected));
    }

    /**
     * From Autobahn WebSocket Server Testcase 5.6
     * <p>
     * pong, then text, then close frames.
     * </p>
     */
    @Test
    public void testParsePongTextClose()
    {
        List<Frame> send = new ArrayList<>();
        send.add(new Frame(OpCode.PONG, "ping"));
        send.add(new Frame(OpCode.TEXT, "hello, world"));
        send.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        RetainableByteBuffer completeBuf = generate(Behavior.SERVER, send);

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, completeBuf);

        capture.assertHasFrame(OpCode.TEXT, 1);
        capture.assertHasFrame(OpCode.CLOSE, 1);
        capture.assertHasFrame(OpCode.PONG, 1);
    }

    /**
     * From Autobahn WebSocket Server Testcase 2.5
     */
    @Test
    public void testParsePongOverSizedPayload()
    {
        byte[] bytes = new byte[126];
        Arrays.fill(bytes, (byte)0x00);

        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(bytes.length + Generator.MAX_HEADER_LENGTH, false);

        byte b;

        // fin + op
        b = 0x00;
        b |= 0x80; // fin on
        b |= 0x0A; // pong
        expected.put(b);

        // mask + len
        b = 0x00;
        b |= 0x00; // no masking
        b |= 0x7E; // 2 byte len
        expected.put(b);

        // 2 byte len
        expected.putShort((short)bytes.length);

        // payload
        expected.put(bytes);

        assertThrows(ProtocolException.class, () -> parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected));
    }

    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testParseRFC6455FragmentedUnmaskedTextMessage() throws InterruptedException
    {
        ParserCapture capture = new ParserCapture();

        RetainableByteBuffer.Mutable buf = RetainableByteBuffer.Mutable.allocate(16, false);

        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // A fragmented unmasked text message (part 1 of 2 "Hel")
        buf.put(new byte[]
            {(byte)0x01, (byte)0x03, 0x48, (byte)0x65, 0x6c});

        // Parse #1
        capture.parse(buf);

        // part 2 of 2 "lo" (A continuation frame of the prior text message)
        // The already parsed bytes are behind the read position, so this appends to the buffer.
        buf.put(new byte[]
            {(byte)0x80, 0x02, 0x6c, 0x6f});

        // Parse #2
        capture.parse(buf);

        capture.assertHasFrame(OpCode.TEXT, 1);
        capture.assertHasFrame(OpCode.CONTINUATION, 1);

        try (Frame txt = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            String actual = txt.getPayloadAsUTF8();
            assertThat("Frame[0].payload", actual, is("Hel"));
        }
        try (Frame txt = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            String actual = txt.getPayloadAsUTF8();
            assertThat("Frame[1].payload", actual, is("lo"));
        }
    }

    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testParseRFC6455SingleMaskedPongRequest() throws InterruptedException
    {
        RetainableByteBuffer.Mutable buf = RetainableByteBuffer.Mutable.allocate(16, false);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // Unmasked Pong request
        buf.put(new byte[]
            {(byte)0x8a, (byte)0x85, 0x37, (byte)0xfa, 0x21, 0x3d, 0x7f, (byte)0x9f, 0x4d, 0x51, 0x58});

        ParserCapture capture = parse(Behavior.SERVER, MAX_ALLOWED_FRAME_SIZE, buf);

        capture.assertHasFrame(OpCode.PONG, 1);

        try (Frame pong = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            String actual = pong.getPayloadAsUTF8();
            assertThat("PongFrame.payload", actual, is("Hello"));
        }
    }

    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testParseRFC6455SingleMaskedTextMessage() throws InterruptedException
    {
        RetainableByteBuffer.Mutable buf = RetainableByteBuffer.Mutable.allocate(16, false);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // A single-frame masked text message
        buf.put(new byte[]
            {(byte)0x81, (byte)0x85, 0x37, (byte)0xfa, 0x21, 0x3d, 0x7f, (byte)0x9f, 0x4d, 0x51, 0x58});

        ParserCapture capture = parse(Behavior.SERVER, MAX_ALLOWED_FRAME_SIZE, buf);

        capture.assertHasFrame(OpCode.TEXT, 1);

        try (Frame txt = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            String actual = txt.getPayloadAsUTF8();
            assertThat("Frame.payload", actual, is("Hello"));
        }
    }

    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testParseRFC6455SingleUnmasked256ByteBinaryMessage() throws InterruptedException
    {
        int dataSize = 256;

        RetainableByteBuffer.Mutable buf = RetainableByteBuffer.Mutable.allocate(dataSize + 10, false);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // 256 bytes binary message in a single unmasked frame
        buf.put(new byte[]
            {(byte)0x82, 0x7E});
        buf.putShort((short)0x01_00); // 16 bit size
        for (int i = 0; i < dataSize; i++)
        {
            buf.put((byte)0x44);
        }

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, buf);

        capture.assertHasFrame(OpCode.BINARY, 1);

        try (Frame bin = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertEquals(dataSize, bin.getPayloadLength(), "Frame.payloadLength");

            byte[] expected = new byte[dataSize];
            Arrays.fill(expected, (byte)0x44);
            try (RetainableByteBuffer data = bin.acquire())
            {
                assertArrayEquals(expected, data.getArray());
            }
        }
    }

    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testParseRFC6455SingleUnmasked64KByteBinaryMessage() throws InterruptedException
    {
        int dataSize = 1024 * 64;

        RetainableByteBuffer.Mutable buf = RetainableByteBuffer.Mutable.allocate(dataSize + 10, false);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // 64 KiloByte binary message in a single unmasked frame
        buf.put(new byte[]
            {(byte)0x82, 0x7F});
        buf.putLong(dataSize); // 64bit size
        for (int i = 0; i < dataSize; i++)
        {
            buf.put((byte)0x77);
        }

        ParserCapture capture = new ParserCapture();
        capture.parse(buf);

        capture.assertHasFrame(OpCode.BINARY, 1);

        try (Frame bin = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertEquals(dataSize, bin.getPayloadLength(), "Frame.payloadLength");
            byte[] expected = new byte[dataSize];
            Arrays.fill(expected, (byte)0x77);
            try (RetainableByteBuffer data = bin.acquire())
            {
                assertArrayEquals(expected, data.getArray());
            }
        }
    }

    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testParseRFC6455SingleUnmaskedPingRequest() throws InterruptedException
    {
        RetainableByteBuffer.Mutable buf = RetainableByteBuffer.Mutable.allocate(16, false);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // Unmasked Ping request
        buf.put(new byte[]
            {(byte)0x89, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f});

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, buf);

        capture.assertHasFrame(OpCode.PING, 1);

        try (Frame ping = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            String actual = ping.getPayloadAsUTF8();
            assertThat("PingFrame.payload", actual, is("Hello"));
        }
    }

    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testParseRFC6455SingleUnmaskedTextMessage() throws InterruptedException
    {
        RetainableByteBuffer.Mutable buf = RetainableByteBuffer.Mutable.allocate(16, false);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // A single-frame unmasked text message
        buf.put(new byte[]
            {(byte)0x81, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f});

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, buf);

        capture.assertHasFrame(OpCode.TEXT, 1);

        try (Frame txt = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            String actual = txt.getPayloadAsUTF8();
            assertThat("Frame.payload", actual, is("Hello"));
        }
    }

    /**
     * From Autobahn WebSocket Server Testcase 1.1.2
     */
    @Test
    public void testParseText125BytePayload() throws InterruptedException
    {
        int length = 125;

        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(length + 5, false);

        expected.put(new byte[]
            {(byte)0x81});
        byte b = 0x00; // no masking
        b |= length & 0x7F;
        expected.put(b);

        for (int i = 0; i < length; ++i)
        {
            expected.put((byte)'*');
        }

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        capture.assertHasFrame(OpCode.TEXT, 1);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertEquals(length, pActual.getPayloadLength(), "Frame.payloadLength");
        }
        // assertEquals("Frame.payload",length,pActual.getPayloadData().length);
    }

    /**
     * From Autobahn WebSocket Server Testcase 1.1.3
     */
    @Test
    public void testParseText126BytePayload() throws InterruptedException
    {
        int length = 126;

        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(length + 5, false);

        expected.put(new byte[]
            {(byte)0x81});
        byte b = 0x00; // no masking
        b |= length & 0x7E;
        expected.put(b);
        expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put((byte)'*');
        }

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        capture.assertHasFrame(OpCode.TEXT, 1);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertEquals(length, pActual.getPayloadLength(), "Frame.payloadLength");
        }
        // assertEquals("Frame.payload",length,pActual.getPayloadData().length);
    }

    /**
     * From Autobahn WebSocket Server Testcase 1.1.4
     */
    @Test
    public void testParseText127BytePayload() throws InterruptedException
    {
        int length = 127;

        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(length + 5, false);

        expected.put(new byte[]
            {(byte)0x81});
        byte b = 0x00; // no masking
        b |= length & 0x7E;
        expected.put(b);
        expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put((byte)'*');
        }

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        capture.assertHasFrame(OpCode.TEXT, 1);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertEquals(length, pActual.getPayloadLength(), "Frame.payloadLength");
        }
        // assertEquals("Frame.payload",length,pActual.getPayloadData().length);
    }

    /**
     * From Autobahn WebSocket Server Testcase 1.1.5
     */
    @Test
    public void testParseText128BytePayload() throws InterruptedException
    {
        int length = 128;

        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(length + 5, false);

        expected.put(new byte[]
            {(byte)0x81});
        byte b = 0x00; // no masking
        b |= 0x7E;
        expected.put(b);
        expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put((byte)'*');
        }

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        capture.assertHasFrame(OpCode.TEXT, 1);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertEquals(length, pActual.getPayloadLength(), "Frame.payloadLength");
        }
        // .assertEquals("Frame.payload",length,pActual.getPayloadData().length);
    }

    /**
     * From Autobahn WebSocket Server Testcase 1.1.6
     */
    @Test
    public void testParseText65535BytePayload() throws InterruptedException
    {
        int length = 65535;

        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(length + 5, false);

        expected.put(new byte[]
            {(byte)0x81});
        byte b = 0x00; // no masking
        b |= 0x7E;
        expected.put(b);
        expected.put(new byte[]
            {(byte)0xff, (byte)0xff});

        for (int i = 0; i < length; ++i)
        {
            expected.put((byte)'*');
        }

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        capture.assertHasFrame(OpCode.TEXT, 1);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertEquals(length, pActual.getPayloadLength(), "Frame.payloadLength");
        }
    }

    /**
     * From Autobahn WebSocket Server Testcase 1.1.7
     */
    @Test
    public void testParseText65536BytePayload() throws InterruptedException
    {
        int length = 65536;

        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(length + 11, false);

        expected.put(new byte[]
            {(byte)0x81});
        byte b = 0x00; // no masking
        b |= 0x7F;
        expected.put(b);
        expected.put(new byte[]
            {0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00});

        for (int i = 0; i < length; ++i)
        {
            expected.put((byte)'*');
        }

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        capture.assertHasFrame(OpCode.TEXT, 1);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertEquals(length, pActual.getPayloadLength(), "Frame.payloadLength");
        }
    }

    /**
     * From Autobahn WebSocket Server Testcase 1.1.1
     */
    @Test
    public void testParseTextEmpty() throws InterruptedException
    {
        RetainableByteBuffer.Mutable expected = RetainableByteBuffer.Mutable.allocate(5, false);

        expected.put(new byte[]
            {(byte)0x81, (byte)0x00});

        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, expected);

        capture.assertHasFrame(OpCode.TEXT, 1);

        try (Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertThat("Frame.payloadLength", pActual.getPayloadLength(), is(0L));
        }
    }

    @Test
    public void testParseTextFrameTooLargeDueToPolicy() throws Exception
    {
        // Artificially small buffer/payload
        final int maxAllowedFrameSize = 1024;

        byte[] utf = new byte[2048];
        Arrays.fill(utf, (byte)'a');

        assertThat("Must be a medium length payload", utf.length, allOf(greaterThan(0x7E), lessThan(0xFFFF)));

        RetainableByteBuffer.Mutable buf = RetainableByteBuffer.Mutable.allocate(utf.length + 8, false);
        buf.put((byte)0x81); // text frame, fin = true
        buf.put((byte)(0x80 | 0x7E)); // 0x7E == 126 (a 2 byte payload length)
        buf.putShort((short)utf.length);
        buf.put(mask);
        putPayload(buf, utf);

        ParserCapture capture = new ParserCapture(Behavior.SERVER);
        capture.getCoreSession().setMaxFrameSize(maxAllowedFrameSize);
        capture.getCoreSession().setAutoFragment(false);
        assertThrows(MessageTooLargeException.class, () -> capture.parse(buf));
    }

    @Test
    public void testParseTextLongMasked() throws Exception
    {
        StringBuilder sb = new StringBuilder();
        // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        for (int i = 0; i < 3500; i++)
        {
            sb.append("Hell\uFF4f Big W\uFF4Frld ");
        }
        sb.append(". The end.");

        String expectedText = sb.toString();
        byte[] utf = expectedText.getBytes(StandardCharsets.UTF_8);

        assertThat("Must be a long length payload", utf.length, greaterThan(0xFFFF));

        RetainableByteBuffer.Mutable buf = RetainableByteBuffer.Mutable.allocate(utf.length + 32, false);
        buf.put((byte)0x81); // text frame, fin = true
        buf.put((byte)(0x80 | 0x7F)); // 0x7F == 127 (a 8 byte payload length)
        buf.putLong(utf.length);
        buf.put(mask);
        putPayload(buf, utf);

        ParserCapture capture = parse(Behavior.SERVER, 100000, buf);

        capture.assertHasFrame(OpCode.TEXT, 1);
        try (Frame txt = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertThat("Frame.payload", txt.getPayloadAsUTF8(), is(expectedText));
        }
    }

    @Test
    public void testParseTextManySmallBuffersNoAutoFragmentation() throws InterruptedException
    {
        // Create frames
        byte[] payload = new byte[65536];
        Arrays.fill(payload, (byte)'*');

        List<Frame> frames = new ArrayList<>();
        frames.add(new Frame(OpCode.TEXT, RetainableByteBuffer.wrap(payload)).withMask(Hex.asByteArray("11223344")));
        frames.add(CloseStatus.toFrame(CloseStatus.NORMAL));

        // Build up raw (network bytes) buffer
        RetainableByteBuffer networkBytes = generate(Behavior.CLIENT, frames);

        // Parse, in 4096 sized windows
        ParserCapture capture = new ParserCapture(Behavior.SERVER);
        capture.getCoreSession().setAutoFragment(false);

        while (networkBytes.hasRemaining())
        {
            int windowSize = (int)Math.min(networkBytes.remaining(), 4096);
            RetainableByteBuffer window = networkBytes.sliceAndConsume(windowSize);
            capture.parse(window);
            window.release();
        }

        assertThat("Frame Count", capture.framesQueue.size(), is(2));
        try (Frame frame = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertThat("Frame[0].opcode", frame.getOpCode(), is(OpCode.TEXT));
            // Should be all '*' characters (if masking is correct)
            try (RetainableByteBuffer actualPayload = frame.acquire())
            {
                assertArrayEquals(payload, actualPayload.getArray());
            }
        }
    }

    @Test
    public void testParseTextMediumMasked() throws Exception
    {
        StringBuilder sb = new StringBuilder();
        // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        for (int i = 0; i < 14; i++)
        {
            sb.append("Hell\uFF4f Medium W\uFF4Frld ");
        }
        sb.append(". The end.");

        String expectedText = sb.toString();
        byte[] utf = expectedText.getBytes(StandardCharsets.UTF_8);

        assertThat("Must be a medium length payload", utf.length, allOf(greaterThan(0x7E), lessThan(0xFFFF)));

        RetainableByteBuffer.Mutable buf = RetainableByteBuffer.Mutable.allocate(utf.length + 10, false);
        buf.put((byte)0x81);
        buf.put((byte)(0x80 | 0x7E)); // 0x7E == 126 (a 2 byte payload length)
        buf.putShort((short)utf.length);
        buf.put(mask);
        putPayload(buf, utf);

        ParserCapture capture = parse(Behavior.SERVER, MAX_ALLOWED_FRAME_SIZE, buf);

        capture.assertHasFrame(OpCode.TEXT, 1);
        try (Frame txt = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertThat("Frame.payload", txt.getPayloadAsUTF8(), is(expectedText));
        }
    }

    @Test
    public void testParseTextShortMasked() throws Exception
    {
        String expectedText = "Hello World";
        byte[] utf = expectedText.getBytes(StandardCharsets.UTF_8);

        RetainableByteBuffer.Mutable buf = RetainableByteBuffer.Mutable.allocate(24, false);
        buf.put((byte)0x81);
        buf.put((byte)(0x80 | utf.length));
        buf.put(mask);
        putPayload(buf, utf);

        ParserCapture capture = parse(Behavior.SERVER, MAX_ALLOWED_FRAME_SIZE, buf);

        capture.assertHasFrame(OpCode.TEXT, 1);
        try (Frame txt = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertThat("Frame.payload", txt.getPayloadAsUTF8(), is(expectedText));
        }
    }

    @Test
    public void testParseTextShortMaskedFragmented() throws Exception
    {
        String part1 = "Hello ";
        String part2 = "World";

        byte[] b1 = part1.getBytes(StandardCharsets.UTF_8);
        byte[] b2 = part2.getBytes(StandardCharsets.UTF_8);

        RetainableByteBuffer.Mutable buf = RetainableByteBuffer.Mutable.allocate(32, false);

        // part 1
        buf.put((byte)0x01); // no fin + text
        buf.put((byte)(0x80 | b1.length));
        buf.put(mask);
        putPayload(buf, b1);

        // part 2
        buf.put((byte)0x80); // fin + continuation
        buf.put((byte)(0x80 | b2.length));
        buf.put(mask);
        putPayload(buf, b2);

        ParserCapture capture = parse(Behavior.SERVER, MAX_ALLOWED_FRAME_SIZE, buf);

        capture.assertHasFrame(OpCode.TEXT, 1);
        capture.assertHasFrame(OpCode.CONTINUATION, 1);
        try (Frame txt = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertThat("Frame[0].payload", txt.getPayloadAsUTF8(), is(part1));
        }
        try (Frame txt = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertThat("Frame[1].payload", txt.getPayloadAsUTF8(), is(part2));
        }
    }

    @Test
    public void testParseTextShortMaskedUtf8() throws Exception
    {
        // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        String expectedText = "Hell\uFF4f W\uFF4Frld";

        byte[] utf = expectedText.getBytes(StandardCharsets.UTF_8);

        RetainableByteBuffer.Mutable buf = RetainableByteBuffer.Mutable.allocate(24, false);
        buf.put((byte)0x81);
        buf.put((byte)(0x80 | utf.length));
        buf.put(mask);
        putPayload(buf, utf);

        ParserCapture capture = parse(Behavior.SERVER, MAX_ALLOWED_FRAME_SIZE, buf);

        capture.assertHasFrame(OpCode.TEXT, 1);
        try (Frame txt = capture.framesQueue.poll(1, TimeUnit.SECONDS))
        {
            assertThat("Frame.payload", txt.getPayloadAsUTF8(), is(expectedText));
        }
    }

    @Test
    public void testParseAutobahn793() throws Exception
    {
        RetainableByteBuffer buf = RetainableByteBuffer.wrap(StringUtil.fromHexString("8882c2887e61c164"));
        Exception e = assertThrows(ProtocolException.class, () -> parse(Behavior.SERVER, MAX_ALLOWED_FRAME_SIZE, buf));
        assertThat(e.getMessage(), Matchers.containsString("Invalid CLOSE Code: "));
    }

    @Test
    public void testParseAutobahn796() throws Exception
    {
        RetainableByteBuffer buf = RetainableByteBuffer.wrap(StringUtil.fromHexString("88824c49cb474fbf"));
        ParserCapture capture = parse(Behavior.SERVER, MAX_ALLOWED_FRAME_SIZE, buf);

        capture.assertHasFrame(OpCode.CLOSE, 1);
        Frame frame = capture.framesQueue.peek();
        assertThat("CloseFrame", frame.getOpCode(), is(OpCode.CLOSE));
        assertThat(CloseStatus.from(frame).getCode(), is(1014));
    }

    @Test
    public void testCompleteDirect() throws Exception
    {
        ByteBuffer data = generate(OpCode.TEXT, "Hello World");
        RetainableByteBuffer.Mutable buffer = RetainableByteBuffer.Mutable.allocate(32, true);
        buffer.put(data);
        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, buffer);

        capture.assertHasFrame(OpCode.TEXT, 1);
        try (ParsedFrame text = (ParsedFrame)capture.framesQueue.take())
        {
            assertEquals("Hello World", text.getPayloadAsUTF8());
        }
    }

    @Test
    public void testComplete() throws Exception
    {
        ByteBuffer data = generate(OpCode.TEXT, "Hello World");
        byte[] array = new byte[32];
        RetainableByteBuffer.Mutable buffer = RetainableByteBuffer.Mutable.wrap(array);
        buffer.put(data);
        ParserCapture capture = parse(Behavior.CLIENT, MAX_ALLOWED_FRAME_SIZE, buffer);

        capture.assertHasFrame(OpCode.TEXT, 1);
        try (ParsedFrame text = (ParsedFrame)capture.framesQueue.take())
        {
            assertEquals("Hello World", text.getPayloadAsUTF8());
        }
    }

    @Test
    public void testPartialDataAutoFragment() throws Exception
    {
        ByteBuffer data = generate(OpCode.TEXT, "Hello World", true);
        int limit = data.limit();
        byte[] array = new byte[32];
        RetainableByteBuffer.Mutable buffer = RetainableByteBuffer.Mutable.wrap(array);

        ParserCapture capture = new ParserCapture(Behavior.SERVER);
        capture.getCoreSession().setAutoFragment(true);

        data.limit(6 + 5);
        buffer.put(data);
        capture.parse(buffer);
        assertEquals(1, capture.framesQueue.size());
        assertEquals(0, buffer.remaining());
        try (ParsedFrame text = (ParsedFrame)capture.framesQueue.take())
        {
            assertFalse(text.isFin());
            assertEquals("Hello", text.getPayloadAsUTF8());
        }

        data.limit(6 + 6);
        buffer.put(data);
        capture.parse(buffer);
        assertEquals(1, capture.framesQueue.size());
        assertEquals(0, buffer.remaining());
        try (ParsedFrame text = (ParsedFrame)capture.framesQueue.take())
        {
            assertFalse(text.isFin());
            assertEquals(" ", text.getPayloadAsUTF8());
        }

        data.limit(limit);
        buffer.put(data);
        capture.parse(buffer);
        assertEquals(1, capture.framesQueue.size());
        assertEquals(0, buffer.remaining());
        capture.assertHasFrame(OpCode.CONTINUATION, 1);
        try (ParsedFrame text = (ParsedFrame)capture.framesQueue.take())
        {
            assertTrue(text.isFin());
            assertEquals("World", text.getPayloadAsUTF8());
        }
    }

    @Test
    public void testPartialDataNoAutoFragment() throws Exception
    {
        ByteBuffer data = generate(OpCode.TEXT, "Hello World");
        int limit = data.limit();
        byte[] array = new byte[32];
        RetainableByteBuffer.Mutable buffer = RetainableByteBuffer.Mutable.wrap(array);

        ParserCapture capture = new ParserCapture();
        capture.getCoreSession().setAutoFragment(false);

        data.limit(5);
        buffer.put(data);
        capture.parse(buffer);
        assertEquals(0, capture.framesQueue.size());
        assertEquals(0, buffer.remaining());

        data.limit(6);
        buffer.put(data);
        capture.parse(buffer);
        assertEquals(0, capture.framesQueue.size());
        assertEquals(0, buffer.remaining());

        data.limit(limit);
        buffer.put(data);
        capture.parse(buffer);
        assertEquals(1, capture.framesQueue.size());
        assertEquals(0, buffer.remaining());

        capture.assertHasFrame(OpCode.TEXT, 1);
        try (ParsedFrame text = (ParsedFrame)capture.framesQueue.take())
        {
            assertEquals("Hello World", text.getPayloadAsUTF8());
        }
    }

    @Test
    public void testPartialControl() throws Exception
    {
        ByteBuffer data = generate(OpCode.PING, "Hello World");
        int limit = data.limit();
        byte[] array = new byte[32];
        RetainableByteBuffer.Mutable buffer = RetainableByteBuffer.Mutable.wrap(array);

        ParserCapture capture = new ParserCapture();

        data.limit(5);
        buffer.put(data);
        capture.parse(buffer);
        assertEquals(0, capture.framesQueue.size());
        assertEquals(0, buffer.remaining());

        data.limit(6);
        buffer.put(data);
        capture.parse(buffer);
        assertEquals(0, capture.framesQueue.size());
        assertEquals(0, buffer.remaining());

        data.limit(limit);
        buffer.put(data);
        capture.parse(buffer);
        assertEquals(1, capture.framesQueue.size());
        assertEquals(0, buffer.remaining());

        capture.assertHasFrame(OpCode.PING, 1);
        try (ParsedFrame text = (ParsedFrame)capture.framesQueue.take())
        {
            assertEquals("Hello World", text.getPayloadAsUTF8());
        }
    }

    private RetainableByteBuffer.Mutable generate(Behavior behavior, List<Frame> frames)
    {
        Generator generator = new Generator();
        int length = Math.toIntExact(frames.stream().mapToLong(frame -> frame.getPayloadLength() + Generator.MAX_HEADER_LENGTH).sum());
        RetainableByteBuffer.Mutable buffer = RetainableByteBuffer.Mutable.allocate(length, false);
        frames.stream()
            .map(frame -> maskIfClient(behavior, frame))
            .forEach(frame -> generator.generateWholeFrame(frame, buffer));
        return buffer;
    }

    private Frame maskIfClient(Behavior behavior, Frame frame)
    {
        if (behavior == Behavior.CLIENT)
            return frame.withMask(new byte[]{0x11, 0x22, 0x33, 0x44});
        return frame;
    }
}
