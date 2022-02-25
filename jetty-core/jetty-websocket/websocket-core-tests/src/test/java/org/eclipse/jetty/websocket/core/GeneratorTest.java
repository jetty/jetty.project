//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.util.Arrays;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.ByteBufferAssert;
import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.core.exception.ProtocolException;
import org.eclipse.jetty.websocket.core.exception.WebSocketException;
import org.eclipse.jetty.websocket.core.internal.ExtensionStack;
import org.eclipse.jetty.websocket.core.internal.Generator;
import org.eclipse.jetty.websocket.core.internal.Negotiated;
import org.eclipse.jetty.websocket.core.internal.WebSocketCoreSession;
import org.eclipse.jetty.websocket.core.internal.util.FrameValidation;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class GeneratorTest
{
    private static final Logger LOG = LoggerFactory.getLogger(Helper.class);

    private static final Generator generator = new Generator();
    private static final WebSocketCoreSession coreSession = newWebSocketCoreSession(Behavior.SERVER);
    private static final WebSocketComponents components = new WebSocketComponents();

    private static WebSocketCoreSession newWebSocketCoreSession(Behavior behavior)
    {
        WebSocketComponents components = new WebSocketComponents();
        ExtensionStack exStack = new ExtensionStack(components, Behavior.SERVER);
        exStack.negotiate(new LinkedList<>(), new LinkedList<>());
        return new WebSocketCoreSession(new TestMessageHandler(), behavior, Negotiated.from(exStack), components);
    }

    /**
     * From Autobahn WebSocket Client Testcase 1.2.2
     * <p>
     * (generates a payload-length using 1 bytes)
     * </p>
     */
    @Test
    public void testGenerateBinary125BytePayload()
    {
        int length = 125;

        ByteBuffer bb = ByteBuffer.allocate(length);

        for (int i = 0; i < length; ++i)
        {
            bb.put("*".getBytes());
        }

        bb.flip();

        Frame binaryFrame = new Frame(OpCode.BINARY).setPayload(bb);

        ByteBuffer actual = generate(binaryFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
            {(byte)0x82});

        byte b = 0x00; // no masking
        b |= length & 0x7F;
        expected.put(b);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        BufferUtil.flipToFlush(expected, 0);

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 1.2.3
     * <p>
     * (generates a payload-length using 2 bytes)
     * </p>
     */
    @Test
    public void testGenerateBinary126BytePayload()
    {
        int length = 126;

        ByteBuffer bb = ByteBuffer.allocate(length);

        for (int i = 0; i < length; ++i)
        {
            bb.put("*".getBytes());
        }

        bb.flip();

        Frame binaryFrame = new Frame(OpCode.BINARY).setPayload(bb);

        ByteBuffer actual = generate(binaryFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
            {(byte)0x82});

        byte b = 0x00; // no masking
        b |= length & 0x7E;
        expected.put(b);

        expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        BufferUtil.flipToFlush(expected, 0);

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 1.2.4
     * <p>
     * (generates a payload-length using 2 bytes)
     * </p>
     */
    @Test
    public void testGenerateBinary127BytePayload()
    {
        int length = 127;

        ByteBuffer bb = ByteBuffer.allocate(length);

        for (int i = 0; i < length; ++i)
        {
            bb.put("*".getBytes());
        }

        bb.flip();

        Frame binaryFrame = new Frame(OpCode.BINARY).setPayload(bb);

        ByteBuffer actual = generate(binaryFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
            {(byte)0x82});

        byte b = 0x00; // no masking
        b |= length & 0x7E;
        expected.put(b);

        expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        BufferUtil.flipToFlush(expected, 0);

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 1.2.5
     * <p>
     * (generates a payload-length using 2 bytes)
     * </p>
     */
    @Test
    public void testGenerateBinary128BytePayload()
    {
        int length = 128;

        ByteBuffer bb = ByteBuffer.allocate(length);

        for (int i = 0; i < length; ++i)
        {
            bb.put("*".getBytes());
        }

        bb.flip();
        Frame binaryFrame = new Frame(OpCode.BINARY).setPayload(bb);

        ByteBuffer actual = generate(binaryFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
            {(byte)0x82});

        byte b = 0x00; // no masking
        b |= 0x7E;
        expected.put(b);

        expected.put((byte)(length >> 8));
        expected.put((byte)(length & 0xFF));

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        BufferUtil.flipToFlush(expected, 0);

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 1.2.6
     * <p>
     * (generates a payload-length using 2 bytes)
     * </p>
     */
    @Test
    public void testGenerateBinary65535BytePayload()
    {
        int length = 65535;

        ByteBuffer bb = ByteBuffer.allocate(length);

        for (int i = 0; i < length; ++i)
        {
            bb.put("*".getBytes());
        }

        bb.flip();

        Frame binaryFrame = new Frame(OpCode.BINARY).setPayload(bb);

        ByteBuffer actual = generate(binaryFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
            {(byte)0x82});

        byte b = 0x00; // no masking
        b |= 0x7E;
        expected.put(b);
        expected.put(new byte[]{(byte)0xff, (byte)0xff});

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        BufferUtil.flipToFlush(expected, 0);

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 1.2.7
     * <p>
     * (generates a payload-length using 8 bytes)
     * </p>
     */
    @Test
    public void testGenerateBinary65536BytePayload()
    {
        int length = 65536;

        ByteBuffer bb = ByteBuffer.allocate(length);

        for (int i = 0; i < length; ++i)
        {
            bb.put("*".getBytes());
        }

        bb.flip();

        Frame binaryFrame = new Frame(OpCode.BINARY).setPayload(bb);

        ByteBuffer actual = generate(binaryFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 11);

        expected.put(new byte[]
            {(byte)0x82});

        byte b = 0x00; // no masking
        b |= 0x7F;
        expected.put(b);
        expected.put(new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00});

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        BufferUtil.flipToFlush(expected, 0);

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 1.2.1
     */
    @Test
    public void testGenerateBinaryEmpty()
    {
        Frame binaryFrame = new Frame(OpCode.BINARY).setPayload(new byte[]{});

        ByteBuffer actual = generate(binaryFrame);

        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
            {(byte)0x82, (byte)0x00});

        BufferUtil.flipToFlush(expected, 0);

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 7.3.2
     */
    @Test
    public void testGenerateClose1BytePayload()
    {
        assertThrows(ProtocolException.class, () -> new CloseStatus(Hex.asByteBuffer("00")));
    }

    @Test
    public void testGenerateCloseCodeNoReason()
    {
        CloseStatus close = new CloseStatus(CloseStatus.NORMAL);
        // 2 byte payload (2 bytes for status code)
        assertGeneratedBytes("880203E8", close.toFrame());
    }

    @Test
    public void testGenerateCloseCodeOkReason()
    {
        CloseStatus close = new CloseStatus(CloseStatus.NORMAL, "OK");
        // 4 byte payload (2 bytes for status code, 2 more for "OK")
        assertGeneratedBytes("880403E84F4B", close.toFrame());
    }

    /**
     * From Autobahn WebSocket Client Testcase 7.3.1
     */
    @Test
    public void testGenerateCloseEmpty()
    {
        // 0 byte payload (no status code)
        assertGeneratedBytes("8800", new Frame(OpCode.CLOSE));
    }

    /**
     * From Autobahn WebSocket Client Testcase 7.3.6
     */
    @Test
    public void testGenerateCloseWithInvalidStatusReason()
    {
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < 124; ++i)
        {
            message.append("*");
        }

        byte[] messageBytes = message.toString().getBytes();

        Frame closeFrame = new Frame(OpCode.CLOSE);

        ByteBuffer bb = ByteBuffer.allocate(Frame.MAX_CONTROL_PAYLOAD + 1); // 126 which is too big for control

        bb.putChar((char)1000);
        bb.put(messageBytes);

        BufferUtil.flipToFlush(bb, 0);

        closeFrame.setPayload(bb);
        assertThrows(ProtocolException.class, () -> FrameValidation.assertValidOutgoing(closeFrame, coreSession));
    }

    /**
     * From Autobahn WebSocket Client Testcase 7.3.3
     */
    @Test
    public void testGenerateCloseWithStatus()
    {
        CloseStatus close = new CloseStatus(1000);

        ByteBuffer actual = generate(close.toFrame());

        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
            {(byte)0x88, (byte)0x02, 0x03, (byte)0xe8});

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 7.3.5
     */
    @Test
    public void testGenerateCloseWithStatusMaxReason()
    {
        StringBuilder message = new StringBuilder();
        for (int i = 0; i < 123; ++i)
        {
            message.append("*");
        }

        CloseStatus close = new CloseStatus(1000, message.toString());

        ByteBuffer actual = generate(close.toFrame());
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

    /**
     * From Autobahn WebSocket Client Testcase 7.3.4
     */
    @Test
    public void testGenerateCloseWithStatusReason()
    {
        String message = "bad cough";
        byte[] messageBytes = message.getBytes();

        CloseStatus close = new CloseStatus(1000, message);

        ByteBuffer actual = generate(close.toFrame());

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

    /**
     * Prevent regression of masking of many packets.
     */
    @Test
    public void testGenerateMaskedManyFrames()
    {
        int pingCount = 2;

        // Prepare frames
        Frame[] frames = new Frame[pingCount + 1];
        for (int i = 0; i < pingCount; i++)
        {
            frames[i] = new Frame(OpCode.PING).setPayload(String.format("ping-%d", i));
        }
        frames[pingCount] = CloseStatus.toFrame(CloseStatus.NORMAL);

        // Mask All Frames
        byte[] maskingKey = Hex.asByteArray("11223344");
        for (Frame f : frames)
        {
            f.setMask(maskingKey);
        }

        // Validate result of generation
        StringBuilder expected = new StringBuilder();
        expected.append("8986").append("11223344");
        expected.append(asMaskedHex("ping-0", maskingKey)); // ping 0
        expected.append("8986").append("11223344");
        expected.append(asMaskedHex("ping-1", maskingKey)); // ping 1
        expected.append("8882").append("11223344");
        byte[] closure = Hex.asByteArray("03E8");
        mask(closure, maskingKey);
        expected.append(Hex.asHex(closure)); // normal closure

        assertGeneratedBytes(expected, frames);
    }

    /**
     * From Autobahn WebSocket Client Testcase 2.4
     */
    @Test
    public void testGeneratePing125BytePayload()
    {
        byte[] bytes = new byte[125];

        for (int i = 0; i < bytes.length; ++i)
        {
            bytes[i] = (byte)(i & 0xff);
        }

        Frame pingFrame = new Frame(OpCode.PING).setPayload(bytes);

        ByteBuffer actual = generate(pingFrame);

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

    /**
     * From Autobahn WebSocket Client Testcase 2.3
     */
    @Test
    public void testGeneratePingBinaryPaylod()
    {
        byte[] bytes = new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08};

        Frame pingFrame = new Frame(OpCode.PING).setPayload(bytes);

        ByteBuffer actual = generate(pingFrame);

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

    /**
     * From Autobahn WebSocket Client Testcase 2.1
     */
    @Test
    public void testGeneratePingEmpty()
    {
        Frame pingFrame = new Frame(OpCode.PING);

        ByteBuffer actual = generate(pingFrame);

        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
            {(byte)0x89, (byte)0x00});

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 2.2
     */
    @Test
    public void testGeneratePingHelloPayload()
    {
        String message = "Hello, world!";
        byte[] messageBytes = StringUtil.getUtf8Bytes(message);

        Frame pingFrame = new Frame(OpCode.PING).setPayload(messageBytes);

        ByteBuffer actual = generate(pingFrame);

        ByteBuffer expected = ByteBuffer.allocate(32);

        expected.put(new byte[]
            {(byte)0x89});

        byte b = 0x00; // no masking
        b |= messageBytes.length & 0x7F;
        expected.put(b);
        expected.put(messageBytes);

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 2.5
     */
    @Test
    public void testGeneratePingOverSizedPayload()
    {
        byte[] bytes = new byte[126];
        Arrays.fill(bytes, (byte)0x00);

        Frame pingFrame = new Frame(OpCode.PING);
        pingFrame.setPayload(ByteBuffer.wrap(bytes));
        assertThrows(WebSocketException.class, () -> FrameValidation.assertValidOutgoing(pingFrame, coreSession));
    }

    /**
     * From Autobahn WebSocket Client Testcase 2.5
     */
    @Test
    public void testGeneratePongOverSizedPayload()
    {
        byte[] bytes = new byte[126];
        Arrays.fill(bytes, (byte)0x00);

        Frame pongFrame = new Frame(OpCode.PONG);
        pongFrame.setPayload(ByteBuffer.wrap(bytes));
        assertThrows(WebSocketException.class, () -> FrameValidation.assertValidOutgoing(pongFrame, coreSession));
    }

    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testGenerateRFC6455FragmentedUnmaskedTextMessage()
    {
        Frame text1 = new Frame(OpCode.TEXT).setPayload("Hel").setFin(false);
        Frame text2 = new Frame(OpCode.CONTINUATION).setPayload("lo");

        ByteBuffer actual1 = generate(text1);
        ByteBuffer actual2 = generate(text2);

        ByteBuffer expected1 = ByteBuffer.allocate(5);

        expected1.put(new byte[]
            {(byte)0x01, (byte)0x03, (byte)0x48, (byte)0x65, (byte)0x6c});

        ByteBuffer expected2 = ByteBuffer.allocate(4);

        expected2.put(new byte[]
            {(byte)0x80, (byte)0x02, (byte)0x6c, (byte)0x6f});

        expected1.flip();
        expected2.flip();

        assertThat("t1 buffers", Hex.asHex(actual1), is(Hex.asHex(expected1)));
        assertThat("t2 buffers", Hex.asHex(actual2), is(Hex.asHex(expected2)));
    }

    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testGenerateRFC6455SingleMaskedPongRequest()
    {
        Frame pong = new Frame(OpCode.PONG).setPayload("Hello");
        pong.setMask(new byte[]
            {0x37, (byte)0xfa, 0x21, 0x3d});

        ByteBuffer actual = generate(pong);

        ByteBuffer expected = ByteBuffer.allocate(11);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // Unmasked Pong request
        expected.put(new byte[]
            {(byte)0x8a, (byte)0x85, 0x37, (byte)0xfa, 0x21, 0x3d, 0x7f, (byte)0x9f, 0x4d, 0x51, 0x58});
        expected.flip(); // make readable

        ByteBufferAssert.assertEquals("pong buffers are not equal", expected, actual);
    }

    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testGenerateRFC6455SingleMaskedTextMessage()
    {
        Frame text = new Frame(OpCode.TEXT).setPayload("Hello");
        text.setMask(new byte[]
            {0x37, (byte)0xfa, 0x21, 0x3d});

        ByteBuffer actual = generate(text);

        ByteBuffer expected = ByteBuffer.allocate(11);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // A single-frame masked text message
        expected.put(new byte[]
            {(byte)0x81, (byte)0x85, 0x37, (byte)0xfa, 0x21, 0x3d, 0x7f, (byte)0x9f, 0x4d, 0x51, 0x58});
        expected.flip(); // make readable

        ByteBufferAssert.assertEquals("masked text buffers are not equal", expected, actual);
    }

    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testGenerateRFC6455SingleUnmasked256ByteBinaryMessage()
    {
        int dataSize = 256;

        Frame binary = new Frame(OpCode.BINARY);
        byte[] payload = new byte[dataSize];
        Arrays.fill(payload, (byte)0x44);
        binary.setPayload(ByteBuffer.wrap(payload));

        ByteBuffer actual = generate(binary);

        ByteBuffer expected = ByteBuffer.allocate(dataSize + Generator.MAX_HEADER_LENGTH);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // 256 bytes binary message in a single unmasked frame
        expected.put(new byte[]
            {(byte)0x82, (byte)0x7E});
        expected.putShort((short)0x01_00);

        for (int i = 0; i < dataSize; i++)
        {
            expected.put((byte)0x44);
        }

        expected.flip();

        ByteBufferAssert.assertEquals("binary buffers are not equal", expected, actual);
    }

    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testGenerateRFC6455SingleUnmasked64KBinaryMessage()
    {
        int dataSize = 1024 * 64;

        Frame binary = new Frame(OpCode.BINARY);
        byte[] payload = new byte[dataSize];
        Arrays.fill(payload, (byte)0x44);
        binary.setPayload(ByteBuffer.wrap(payload));

        ByteBuffer actual = generate(binary);

        ByteBuffer expected = ByteBuffer.allocate(dataSize + 10);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // 64k bytes binary message in a single unmasked frame
        expected.put(new byte[]
            {(byte)0x82, (byte)0x7F});
        expected.putInt(0x00_00_00_00);
        expected.putInt(0x00_01_00_00);

        for (int i = 0; i < dataSize; i++)
        {
            expected.put((byte)0x44);
        }

        expected.flip();

        ByteBufferAssert.assertEquals("binary buffers are not equal", expected, actual);
    }

    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testGenerateRFC6455SingleUnmaskedPingRequest() throws Exception
    {
        Frame ping = new Frame(OpCode.PING).setPayload("Hello");

        ByteBuffer actual = generate(ping);

        ByteBuffer expected = ByteBuffer.allocate(10);
        expected.put(new byte[]
            {(byte)0x89, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f});
        expected.flip(); // make readable

        ByteBufferAssert.assertEquals("Ping buffers", expected, actual);
    }

    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testGenerateRFC6455SingleUnmaskedTextMessage()
    {
        Frame text = new Frame(OpCode.TEXT).setPayload("Hello");

        ByteBuffer actual = generate(text);

        ByteBuffer expected = ByteBuffer.allocate(10);

        expected.put(new byte[]
            {(byte)0x81, (byte)0x05, (byte)0x48, (byte)0x65, (byte)0x6c, (byte)0x6c, (byte)0x6f});

        expected.flip();

        ByteBufferAssert.assertEquals("t1 buffers are not equal", expected, actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 1.1.2
     */
    @Test
    public void testGenerateText125BytePaylod()
    {
        int length = 125;
        byte[] buf = new byte[length];
        Arrays.fill(buf, (byte)'*');
        String text = new String(buf, StandardCharsets.UTF_8);

        Frame textFrame = new Frame(OpCode.TEXT).setPayload(text);

        ByteBuffer actual = generate(textFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
            {(byte)0x81});

        byte b = 0x00; // no masking
        b |= length & 0x7F;
        expected.put(b);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 1.1.3
     */
    @Test
    public void testGenerateText126BytePaylod()
    {
        int length = 126;

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }

        Frame textFrame = new Frame(OpCode.TEXT).setPayload(builder.toString());

        ByteBuffer actual = generate(textFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
            {(byte)0x81});

        byte b = 0x00; // no masking
        b |= length & 0x7E;
        expected.put(b);

        expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 1.1.4
     */
    @Test
    public void testGenerateText127BytePayload()
    {
        int length = 127;

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }

        Frame textFrame = new Frame(OpCode.TEXT).setPayload(builder.toString());

        ByteBuffer actual = generate(textFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
            {(byte)0x81});

        byte b = 0x00; // no masking
        b |= length & 0x7E;
        expected.put(b);

        expected.putShort((short)length);

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 1.1.5
     */
    @Test
    public void testGenerateText128BytePayload()
    {
        int length = 128;

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }

        Frame textFrame = new Frame(OpCode.TEXT).setPayload(builder.toString());

        ByteBuffer actual = generate(textFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
            {(byte)0x81});

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

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 1.1.6
     */
    @Test
    public void testGenerateText65535BytePayload()
    {
        int length = 65535;

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }

        Frame textFrame = new Frame(OpCode.TEXT).setPayload(builder.toString());

        ByteBuffer actual = generate(textFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 5);

        expected.put(new byte[]
            {(byte)0x81});

        byte b = 0x00; // no masking
        b |= 0x7E;
        expected.put(b);
        expected.put(new byte[]
            {(byte)0xff, (byte)0xff});

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 1.1.7
     */
    @Test
    public void testGenerateText65536BytePayload()
    {
        int length = 65536;

        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }

        Frame textFrame = new Frame(OpCode.TEXT).setPayload(builder.toString());

        ByteBuffer actual = generate(textFrame);

        ByteBuffer expected = ByteBuffer.allocate(length + 11);

        expected.put(new byte[]
            {(byte)0x81});

        byte b = 0x00; // no masking
        b |= 0x7F;
        expected.put(b);
        expected.put(new byte[]
            {0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00});

        for (int i = 0; i < length; ++i)
        {
            expected.put("*".getBytes());
        }

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 1.1.1
     */
    @Test
    public void testGenerateTextEmpty()
    {
        Frame textFrame = new Frame(OpCode.TEXT).setPayload("");

        ByteBuffer actual = generate(textFrame);

        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
            {(byte)0x81, (byte)0x00});

        expected.flip();

        ByteBufferAssert.assertEquals("buffers do not match", expected, actual);
    }

    @Test
    public void testGenerateTextHello()
    {
        Frame frame = new Frame(OpCode.TEXT).setPayload("Hello");
        byte[] utf = StringUtil.getUtf8Bytes("Hello");
        assertGeneratedBytes("8105" + Hex.asHex(utf), frame);
    }

    @Test
    public void testGenerateTextMasked()
    {
        Frame frame = new Frame(OpCode.TEXT).setPayload("Hello");
        byte[] maskingKey = Hex.asByteArray("11223344");
        frame.setMask(maskingKey);

        // what is expected
        StringBuilder expected = new StringBuilder();
        expected.append("8185").append("11223344");
        expected.append(asMaskedHex("Hello", maskingKey));

        // validate
        assertGeneratedBytes(expected, frame);
    }

    @Test
    public void testGenerateTextMaskedOffsetSourceByteBuffer()
    {
        ByteBuffer payload = ByteBuffer.allocate(100);
        payload.position(5);
        payload.put(StringUtil.getUtf8Bytes("Hello"));
        payload.flip();
        payload.position(5);
        // at this point, we have a ByteBuffer of 100 bytes.
        // but only a few bytes in the middle are made available for the payload.
        // we are testing that masking works as intended, even if the provided
        // payload does not start at position 0.
        LOG.debug("Payload = {}", BufferUtil.toDetailString(payload));
        Frame frame = new Frame(OpCode.TEXT).setPayload(payload);
        byte[] maskingKey = Hex.asByteArray("11223344");
        frame.setMask(maskingKey);

        // what is expected
        StringBuilder expected = new StringBuilder();
        expected.append("8185").append("11223344");
        expected.append(asMaskedHex("Hello", maskingKey));

        // validate
        assertGeneratedBytes(expected, frame);
    }

    /**
     * Test the windowed generate of a frame that has no masking.
     */
    @Test
    public void testGenerateWindowed()
    {
        // A decent sized frame, no masking
        byte[] payload = new byte[10240];
        Arrays.fill(payload, (byte)0x44);

        Frame frame = new Frame(OpCode.BINARY).setPayload(payload);

        // Generate
        int windowSize = 1024;
        Helper helper = new Helper();
        ByteBuffer completeBuffer = helper.generate(frame);

        // Validate
        int expectedHeaderSize = 4;
        int expectedSize = payload.length + expectedHeaderSize;
        int expectedParts = 1;

        helper.assertTotalParts(expectedParts);
        helper.assertTotalBytes(payload.length + expectedHeaderSize);

        assertThat("Generated Buffer", completeBuffer.remaining(), is(expectedSize));
    }

    /**
     * This is to prevent a regression in the masking of many frames.
     */
    @Test
    public void testGenerateWithMasking() throws Exception
    {
        // A decent sized frame, with masking
        byte[] payload = new byte[10240];
        Arrays.fill(payload, (byte)0x55);

        byte[] mask = new byte[]
            {0x2A, (byte)0xF0, 0x0F, 0x00};

        Frame frame = new Frame(OpCode.BINARY).setPayload(payload);
        frame.setMask(mask); // masking!

        // Generate
        Helper helper = new Helper();
        ByteBuffer completeBuffer = helper.generate(frame);

        // Validate
        int expectedHeaderSize = 8;
        int expectedSize = payload.length + expectedHeaderSize;
        int expectedParts = 1;

        helper.assertTotalParts(expectedParts);
        helper.assertTotalBytes(expectedSize);

        assertThat("Generated Buffer", completeBuffer.remaining(), is(expectedSize));

        // Parse complete buffer.
        ParserCapture capture = new ParserCapture(true, Behavior.SERVER);

        capture.parse(completeBuffer);

        // Assert validity of frame
        Frame actual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Frame.opcode", actual.getOpCode(), Matchers.is(OpCode.BINARY));
        assertThat("Frame.payloadLength", actual.getPayloadLength(), is(payload.length));

        // Validate payload contents for proper masking
        ByteBuffer actualData = actual.getPayload().slice();
        assertThat("Frame.payload.remaining", actualData.remaining(), is(payload.length));
        while (actualData.remaining() > 0)
        {
            assertThat("Actual.payload[" + actualData.position() + "]", actualData.get(), is((byte)0x55));
        }
    }

    public static class Helper
    {
        int totalParts;
        int totalBytes;

        public Helper()
        {
            this.totalParts = 0;
            this.totalBytes = 0;
        }

        public void assertTotalBytes(int expectedBytes)
        {
            assertThat("Generated Bytes", totalBytes, is(expectedBytes));
        }

        public void assertTotalParts(int expectedParts)
        {
            assertThat("Generated Parts", totalParts, is(expectedParts));
        }

        public ByteBuffer generate(Frame... frames)
        {
            // Create Buffer to hold all generated frames in a single buffer
            int completeBufSize = 0;
            for (Frame f : frames)
            {
                completeBufSize += Generator.MAX_HEADER_LENGTH + f.getPayloadLength();
            }

            ByteBuffer completeBuf = BufferUtil.allocate(completeBufSize);

            // Generate from all frames
            for (Frame f : frames)
            {
                int remaining = completeBuf.remaining();
                generator.generateHeader(f, completeBuf);
                totalBytes += completeBuf.remaining() - remaining;

                remaining = completeBuf.remaining();
                generator.generatePayload(f, completeBuf);

                if (completeBuf.remaining() - remaining > 0)
                {
                    totalBytes += completeBuf.remaining() - remaining;
                    totalParts++;
                }
            }

            // Return results
            return completeBuf;
        }
    }

    private void assertGeneratedBytes(CharSequence expectedBytes, Frame... frames)
    {
        // collect up all frames as single ByteBuffer
        ByteBuffer buffer = generate(frames);
        // Get hex String form of all frames buffer.
        String actual = Hex.asHex(buffer);
        // Validate
        assertThat("Buffer", actual, is(expectedBytes.toString()));
    }

    private String asMaskedHex(String str, byte[] maskingKey)
    {
        byte[] utf = StringUtil.getUtf8Bytes(str);
        mask(utf, maskingKey);
        return Hex.asHex(utf);
    }

    private void mask(byte[] buf, byte[] maskingKey)
    {
        int size = buf.length;
        for (int i = 0; i < size; i++)
        {
            buf[i] ^= maskingKey[i % 4];
        }
    }

    private static ByteBuffer generate(Frame... frames)
    {
        int length = Arrays.stream(frames).mapToInt(frame -> frame.getPayloadLength() + Generator.MAX_HEADER_LENGTH).sum();
        ByteBuffer buffer = BufferUtil.allocate(length);
        Arrays.stream(frames).forEach(frame -> generator.generateWholeFrame(frame, buffer));
        return buffer;
    }
}
