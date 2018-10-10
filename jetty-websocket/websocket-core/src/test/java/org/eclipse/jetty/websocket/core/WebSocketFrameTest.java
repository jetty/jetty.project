//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import org.eclipse.jetty.io.LeakTrackingByteBufferPool;
import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.websocket.core.internal.Generator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class WebSocketFrameTest
{
    public TestableLeakTrackingBufferPool bufferPool = new TestableLeakTrackingBufferPool(WebSocketFrameTest.class);

    @AfterEach
    public void afterEach()
    {
        bufferPool.assertNoLeaks();
    }

    private Generator generator;

    private ByteBuffer generateWholeFrame(Generator generator, Frame frame)
    {
        ByteBuffer buf = ByteBuffer.allocate(frame.getPayloadLength() + Generator.MAX_HEADER_LENGTH);
        generator.generateWholeFrame(frame,buf);
        BufferUtil.flipToFlush(buf,0);
        return buf;
    }

    @BeforeEach
    public void initGenerator()
    {
        generator = new Generator(bufferPool);
    }

    @AfterEach
    public void verifyNoLeaks()
    {
        //TODO is this right and should it be other way around
        bufferPool.clearTracking();
        assertNoLeaks(bufferPool);
    }

    public void assertNoLeaks(LeakTrackingByteBufferPool pool)
    {
        String id = this.getClass().getName();

        assertThat("Leaked Acquires Count for [" + id + "]", pool.getLeakedAcquires(), is(0L));
        assertThat("Leaked Releases Count for [" + id + "]", pool.getLeakedReleases(), is(0L));
        assertThat("Leaked Resource Count for [" + id + "]", pool.getLeakedResources(), is(0L));
    }

    private void assertFrameHex(String message, String expectedHex, ByteBuffer actual)
    {
        String actualHex = Hex.asHex(actual);
        assertThat("Generated Frame:" + message,actualHex,is(expectedHex));
    }

    @Test
    public void testInvalidClose()
    {
        Frame frame = new Frame(OpCode.CLOSE).setFin(false);
        ByteBuffer actual = generateWholeFrame(generator,frame);
        String expected = "0800";
        assertFrameHex("Invalid Close Frame",expected,actual);
    }

    @Test
    public void testInvalidPing()
    {
        Frame frame = new Frame(OpCode.PING).setFin(false);
        ByteBuffer actual = generateWholeFrame(generator,frame);
        String expected = "0900";
        assertFrameHex("Invalid Ping Frame",expected,actual);
    }

    @Test
    public void testValidClose()
    {
        Frame frame = CloseStatus.toFrame(CloseStatus.NORMAL);
        ByteBuffer actual = generateWholeFrame(generator,frame);
        String expected = "880203E8";
        assertFrameHex("Valid Close Frame",expected,actual);
    }

    @Test
    public void testValidPing()
    {
        Frame frame = new Frame(OpCode.PING);
        ByteBuffer actual = generateWholeFrame(generator,frame);
        String expected = "8900";
        assertFrameHex("Valid Ping Frame",expected,actual);
    }
    
    @Test
    public void testRsv1()
    {
        Frame frame = new Frame(OpCode.TEXT);
        frame.setPayload("Hi");
        frame.setRsv1(true);
        ByteBuffer actual = generateWholeFrame(generator,frame);
        String expected = "C1024869";
        assertFrameHex("Text Frame with RSV1",expected,actual);
    }
    
    @Test
    public void testRsv2()
    {
        Frame frame = new Frame(OpCode.TEXT);
        frame.setPayload("Hi");
        frame.setRsv2(true);
        ByteBuffer actual = generateWholeFrame(generator,frame);
        String expected = "A1024869";
        assertFrameHex("Text Frame with RSV2",expected,actual);
    }
    
    @Test
    public void testRsv3()
    {
        Frame frame = new Frame(OpCode.TEXT);
        frame.setPayload("Hi");
        frame.setRsv3(true);
        ByteBuffer actual = generateWholeFrame(generator,frame);
        String expected = "91024869";
        assertFrameHex("Text Frame with RSV3",expected,actual);
    }
    
    @Test 
    public void testDemask()
    {
        for (int i=0; i<=8; i++)
        {
            Frame frame = new Frame(OpCode.BINARY);
            frame.setPayload(TypeUtil.fromHexString("0000FFFF000FFFF0".substring(0,i*2)));
            frame.setMask(TypeUtil.fromHexString("FF00FF00"));
            frame.demask();
            assertEquals("Ff0000FfFf0f00F0".substring(0,i*2),BufferUtil.toHexString(frame.getPayload()), "len="+i);
        }
    }
}
