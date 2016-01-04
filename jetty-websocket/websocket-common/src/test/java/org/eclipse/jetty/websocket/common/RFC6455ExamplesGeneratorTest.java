//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.Arrays;

import org.eclipse.jetty.websocket.common.frames.BinaryFrame;
import org.eclipse.jetty.websocket.common.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.common.frames.PingFrame;
import org.eclipse.jetty.websocket.common.frames.PongFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.common.test.ByteBufferAssert;
import org.eclipse.jetty.websocket.common.test.UnitGenerator;
import org.junit.Test;

public class RFC6455ExamplesGeneratorTest
{
    private static final int FUDGE = 32;

    @Test
    public void testFragmentedUnmaskedTextMessage()
    {
        WebSocketFrame text1 = new TextFrame().setPayload("Hel").setFin(false);
        WebSocketFrame text2 = new ContinuationFrame().setPayload("lo");

        ByteBuffer actual1 = UnitGenerator.generate(text1);
        ByteBuffer actual2 = UnitGenerator.generate(text2);

        ByteBuffer expected1 = ByteBuffer.allocate(5);

        expected1.put(new byte[]
                { (byte)0x01, (byte)0x03, (byte)0x48, (byte)0x65, (byte)0x6c });

        ByteBuffer expected2 = ByteBuffer.allocate(4);

        expected2.put(new byte[]
                { (byte)0x80, (byte)0x02, (byte)0x6c, (byte)0x6f });

        expected1.flip();
        expected2.flip();

        ByteBufferAssert.assertEquals("t1 buffers are not equal",expected1,actual1);
        ByteBufferAssert.assertEquals("t2 buffers are not equal",expected2,actual2);
    }

    @Test
    public void testSingleMaskedPongRequest()
    {
        PongFrame pong = new PongFrame().setPayload("Hello");
        pong.setMask(new byte[]
                { 0x37, (byte)0xfa, 0x21, 0x3d });

        ByteBuffer actual = UnitGenerator.generate(pong);

        ByteBuffer expected = ByteBuffer.allocate(11);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // Unmasked Pong request
        expected.put(new byte[]
                { (byte)0x8a, (byte)0x85, 0x37, (byte)0xfa, 0x21, 0x3d, 0x7f, (byte)0x9f, 0x4d, 0x51, 0x58 });
        expected.flip(); // make readable

        ByteBufferAssert.assertEquals("pong buffers are not equal",expected,actual);
    }

    @Test
    public void testSingleMaskedTextMessage()
    {
        WebSocketFrame text = new TextFrame().setPayload("Hello");
        text.setMask(new byte[]
                { 0x37, (byte)0xfa, 0x21, 0x3d });

        ByteBuffer actual = UnitGenerator.generate(text);

        ByteBuffer expected = ByteBuffer.allocate(11);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // A single-frame masked text message
        expected.put(new byte[]
                { (byte)0x81, (byte)0x85, 0x37, (byte)0xfa, 0x21, 0x3d, 0x7f, (byte)0x9f, 0x4d, 0x51, 0x58 });
        expected.flip(); // make readable

        ByteBufferAssert.assertEquals("masked text buffers are not equal",expected,actual);
    }

    @Test
    public void testSingleUnmasked256ByteBinaryMessage()
    {
        int dataSize = 256;

        BinaryFrame binary = new BinaryFrame();
        byte payload[] = new byte[dataSize];
        Arrays.fill(payload,(byte)0x44);
        binary.setPayload(ByteBuffer.wrap(payload));

        ByteBuffer actual = UnitGenerator.generate(binary);

        ByteBuffer expected = ByteBuffer.allocate(dataSize + FUDGE);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // 256 bytes binary message in a single unmasked frame
        expected.put(new byte[]
                { (byte)0x82, (byte)0x7E });
        expected.putShort((short)0x01_00);

        for (int i = 0; i < dataSize; i++)
        {
            expected.put((byte)0x44);
        }

        expected.flip();

        ByteBufferAssert.assertEquals("binary buffers are not equal",expected,actual);
    }

    @Test
    public void testSingleUnmasked64KBinaryMessage()
    {
        int dataSize = 1024 * 64;

        BinaryFrame binary = new BinaryFrame();
        byte payload[] = new byte[dataSize];
        Arrays.fill(payload,(byte)0x44);
        binary.setPayload(ByteBuffer.wrap(payload));

        ByteBuffer actual = UnitGenerator.generate(binary);

        ByteBuffer expected = ByteBuffer.allocate(dataSize + 10);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // 64k bytes binary message in a single unmasked frame
        expected.put(new byte[]
                { (byte)0x82, (byte)0x7F });
        expected.putInt(0x00_00_00_00);
        expected.putInt(0x00_01_00_00);

        for (int i = 0; i < dataSize; i++)
        {
            expected.put((byte)0x44);
        }

        expected.flip();

        ByteBufferAssert.assertEquals("binary buffers are not equal",expected,actual);
    }

    @Test
    public void testSingleUnmaskedPingRequest() throws Exception
    {
        PingFrame ping = new PingFrame().setPayload("Hello");

        ByteBuffer actual = UnitGenerator.generate(ping);

        ByteBuffer expected = ByteBuffer.allocate(10);
        expected.put(new byte[]
                { (byte)0x89, (byte)0x05, (byte)0x48, (byte)0x65, (byte)0x6c, (byte)0x6c, (byte)0x6f });
        expected.flip(); // make readable

        ByteBufferAssert.assertEquals("Ping buffers",expected,actual);
    }

    @Test
    public void testSingleUnmaskedTextMessage()
    {
        WebSocketFrame text = new TextFrame().setPayload("Hello");

        ByteBuffer actual = UnitGenerator.generate(text);

        ByteBuffer expected = ByteBuffer.allocate(10);

        expected.put(new byte[]
                { (byte)0x81, (byte)0x05, (byte)0x48, (byte)0x65, (byte)0x6c, (byte)0x6c, (byte)0x6f });

        expected.flip();

        ByteBufferAssert.assertEquals("t1 buffers are not equal",expected,actual);
    }
}
