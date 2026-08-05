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

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.websocket.core.internal.Generator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class WebSocketFrameTest
{
    private final Generator generator = new Generator();

    private RetainableByteBuffer generateWholeFrame(Generator generator, Frame frame)
    {
        RetainableByteBuffer.Mutable buffer = RetainableByteBuffer.Mutable.allocate(frame.getPayloadLength() + Generator.MAX_HEADER_LENGTH, false);
        generator.generateWholeFrame(frame, buffer);
        return buffer;
    }

    private void assertFrameHex(String message, String expectedHex, RetainableByteBuffer actual)
    {
        assertArrayEquals(StringUtil.fromHexString(expectedHex), actual.getArray(), "Generated Frame:" + message);
    }

    @Test
    public void testInvalidClose()
    {
        Frame frame = new Frame(OpCode.CLOSE).setFin(false);
        RetainableByteBuffer actual = generateWholeFrame(generator, frame);
        String expected = "0800";
        assertFrameHex("Invalid Close Frame", expected, actual);
    }

    @Test
    public void testInvalidPing()
    {
        Frame frame = new Frame(OpCode.PING).setFin(false);
        RetainableByteBuffer actual = generateWholeFrame(generator, frame);
        String expected = "0900";
        assertFrameHex("Invalid Ping Frame", expected, actual);
    }

    @Test
    public void testValidClose()
    {
        Frame frame = CloseStatus.toFrame(CloseStatus.NORMAL);
        RetainableByteBuffer actual = generateWholeFrame(generator, frame);
        String expected = "880203E8";
        assertFrameHex("Valid Close Frame", expected, actual);
    }

    @Test
    public void testValidPing()
    {
        Frame frame = new Frame(OpCode.PING);
        RetainableByteBuffer actual = generateWholeFrame(generator, frame);
        String expected = "8900";
        assertFrameHex("Valid Ping Frame", expected, actual);
    }

    @Test
    public void testRsv1()
    {
        Frame frame = new Frame(OpCode.TEXT);
        frame.setPayload("Hi");
        frame.setRsv1(true);
        RetainableByteBuffer actual = generateWholeFrame(generator, frame);
        String expected = "C1024869";
        assertFrameHex("Text Frame with RSV1", expected, actual);
    }

    @Test
    public void testRsv2()
    {
        Frame frame = new Frame(OpCode.TEXT);
        frame.setPayload("Hi");
        frame.setRsv2(true);
        RetainableByteBuffer actual = generateWholeFrame(generator, frame);
        String expected = "A1024869";
        assertFrameHex("Text Frame with RSV2", expected, actual);
    }

    @Test
    public void testRsv3()
    {
        Frame frame = new Frame(OpCode.TEXT);
        frame.setPayload("Hi");
        frame.setRsv3(true);
        RetainableByteBuffer actual = generateWholeFrame(generator, frame);
        String expected = "91024869";
        assertFrameHex("Text Frame with RSV3", expected, actual);
    }

    @Test
    public void testDemask()
    {
        for (int i = 0; i <= 8; i++)
        {
            Frame frame = new Frame(OpCode.BINARY);
            frame.setPayload(StringUtil.fromHexString("0000FFFF000FFFF0".substring(0, i * 2)));
            frame.setMask(StringUtil.fromHexString("FF00FF00"));
            frame.demask();
            assertEquals("Ff0000FfFf0f00F0".substring(0, i * 2), BufferUtil.toHexString(frame.getPayload()), "len=" + i);
        }
    }
}
