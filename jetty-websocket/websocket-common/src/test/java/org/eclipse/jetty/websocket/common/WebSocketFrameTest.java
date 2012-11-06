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

package org.eclipse.jetty.websocket.common;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.junit.BeforeClass;
import org.junit.Test;

public class WebSocketFrameTest
{
    private static Generator strictGenerator;
    private static Generator laxGenerator;

    @BeforeClass
    public static void initGenerator()
    {
        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        ByteBufferPool bufferPool = new MappedByteBufferPool();
        strictGenerator = new Generator(policy,bufferPool);
        laxGenerator = new Generator(policy,bufferPool,false);
    }

    private void assertEqual(String message, ByteBuffer expected, ByteBuffer actual)
    {
        BufferUtil.flipToFlush(expected,0);

        ByteBufferAssert.assertEquals(message,expected,actual);
    }

    @Test
    public void testLaxInvalidClose()
    {
        WebSocketFrame frame = new WebSocketFrame(OpCode.CLOSE).setFin(false);
        ByteBuffer actual = laxGenerator.generate(frame);
        ByteBuffer expected = ByteBuffer.allocate(2);
        expected.put((byte)0x08);
        expected.put((byte)0x00);

        assertEqual("Lax Invalid Close Frame",expected,actual);
    }

    @Test
    public void testLaxInvalidPing()
    {
        WebSocketFrame frame = new WebSocketFrame(OpCode.PING).setFin(false);
        ByteBuffer actual = laxGenerator.generate(frame);
        ByteBuffer expected = ByteBuffer.allocate(2);
        expected.put((byte)0x09);
        expected.put((byte)0x00);

        assertEqual("Lax Invalid Ping Frame",expected,actual);
    }

    @Test
    public void testStrictValidClose()
    {
        CloseInfo close = new CloseInfo(StatusCode.NORMAL);
        ByteBuffer actual = strictGenerator.generate(close.asFrame());
        ByteBuffer expected = ByteBuffer.allocate(4);
        expected.put((byte)0x88);
        expected.put((byte)0x02);
        expected.put((byte)0x03);
        expected.put((byte)0xE8);

        assertEqual("Strict Valid Close Frame",expected,actual);
    }

    @Test
    public void testStrictValidPing()
    {
        WebSocketFrame frame = new WebSocketFrame(OpCode.PING);
        ByteBuffer actual = strictGenerator.generate(frame);
        ByteBuffer expected = ByteBuffer.allocate(2);
        expected.put((byte)0x89);
        expected.put((byte)0x00);

        assertEqual("Strict Valid Ping Frame",expected,actual);
    }
}
