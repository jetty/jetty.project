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

package org.eclipse.jetty.websocket.core.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Generator;
import org.eclipse.jetty.websocket.core.LeakTrackingBufferPoolRule;
import org.eclipse.jetty.websocket.core.WebSocketConstants;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;

public class WebSocketFrameTest
{
    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule(WebSocketFrameTest.class);

    private Generator strictGenerator;
    private Generator laxGenerator;

    private ByteBuffer generateWholeFrame(Generator generator, org.eclipse.jetty.websocket.core.frames.Frame frame)
    {
        ByteBuffer buf = ByteBuffer.allocate(frame.getPayloadLength() + Generator.MAX_HEADER_LENGTH);
        generator.generateWholeFrame(frame,buf);
        BufferUtil.flipToFlush(buf,0);
        return buf;
    }

    @Before
    public void initGenerator()
    {
        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        strictGenerator = new Generator(policy,bufferPool);
        laxGenerator = new Generator(policy,bufferPool);
    }

    private void assertFrameHex(String message, String expectedHex, ByteBuffer actual)
    {
        String actualHex = Hex.asHex(actual);
        Assert.assertThat("Generated Frame:" + message,actualHex,is(expectedHex));
    }

    @Test
    public void testLaxInvalidClose()
    {
        Frame frame = new Frame(OpCode.CLOSE).setFin(false);
        ByteBuffer actual = generateWholeFrame(laxGenerator,frame);
        String expected = "0800";
        assertFrameHex("Lax Invalid Close Frame",expected,actual);
    }

    @Test
    public void testLaxInvalidPing()
    {
        Frame frame = new Frame(OpCode.PING).setFin(false);
        ByteBuffer actual = generateWholeFrame(laxGenerator,frame);
        String expected = "0900";
        assertFrameHex("Lax Invalid Ping Frame",expected,actual);
    }

    @Test
    public void testStrictValidClose()
    {
        Frame frame = CloseStatus.toFrame(WebSocketConstants.NORMAL);
        ByteBuffer actual = generateWholeFrame(strictGenerator,frame);
        String expected = "880203E8";
        assertFrameHex("Strict Valid Close Frame",expected,actual);
    }

    @Test
    public void testStrictValidPing()
    {
        Frame frame = new Frame(OpCode.PING);
        ByteBuffer actual = generateWholeFrame(strictGenerator,frame);
        String expected = "8900";
        assertFrameHex("Strict Valid Ping Frame",expected,actual);
    }
    
    @Test
    public void testRsv1()
    {
        Frame frame = new Frame(OpCode.TEXT);
        frame.setPayload("Hi");
        frame.setRsv1(true);
        laxGenerator.setRsv1InUse(true);
        ByteBuffer actual = generateWholeFrame(laxGenerator,frame);
        String expected = "C1024869";
        assertFrameHex("Lax Text Frame with RSV1",expected,actual);
    }
    
    @Test
    public void testRsv2()
    {
        Frame frame = new Frame(OpCode.TEXT);
        frame.setPayload("Hi");
        frame.setRsv2(true);
        laxGenerator.setRsv2InUse(true);
        ByteBuffer actual = generateWholeFrame(laxGenerator,frame);
        String expected = "A1024869";
        assertFrameHex("Lax Text Frame with RSV2",expected,actual);
    }
    
    @Test
    public void testRsv3()
    {
        Frame frame = new Frame(OpCode.TEXT);
        frame.setPayload("Hi");
        frame.setRsv3(true);
        laxGenerator.setRsv3InUse(true);
        ByteBuffer actual = generateWholeFrame(laxGenerator,frame);
        String expected = "91024869";
        assertFrameHex("Lax Text Frame with RSV3",expected,actual);
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
            assertEquals("len="+i,"Ff0000FfFf0f00F0".substring(0,i*2),BufferUtil.toHexString(frame.getPayload()));
        }
    }
    
}
