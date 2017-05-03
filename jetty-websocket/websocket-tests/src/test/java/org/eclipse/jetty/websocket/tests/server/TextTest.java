//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests.server;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.common.frames.DataFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.eclipse.jetty.websocket.tests.DataUtils;
import org.eclipse.jetty.websocket.tests.LocalFuzzer;
import org.junit.Test;

/**
 * UTF-8 Tests
 */
public class TextTest extends AbstractLocalServerCase
{
    /**
     * Echo 16MB text message (1 frame)
     * <p>
     * From Autobahn WebSocket Server Testcase 9.1.6
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_16mb_SingleFrame() throws Exception
    {
        byte utf[] = new byte[16 * MBYTE];
        Arrays.fill(utf,(byte)'y');
        ByteBuffer buf = ByteBuffer.wrap(utf);
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(DataUtils.copyOf(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Echo 1MB text message (1 frame)
     * <p>
     * From Autobahn WebSocket Server Testcase 9.1.3
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_1mb_SingleFrame() throws Exception
    {
        //noinspection PointlessArithmeticExpression
        byte utf[] = new byte[1 * MBYTE];
        Arrays.fill(utf,(byte)'y');
        ByteBuffer buf = ByteBuffer.wrap(utf);
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(DataUtils.copyOf(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Echo 256KB text message (1 frame)
     * <p>
     * From Autobahn WebSocket Server Testcase 9.1.2
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_256k_SingleFrame() throws Exception
    {
        byte utf[] = new byte[256 * KBYTE];
        Arrays.fill(utf,(byte)'y');
        ByteBuffer buf = ByteBuffer.wrap(utf);
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(DataUtils.copyOf(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send 4MB text message in multiple frames.
     * <p>
     * From Autobahn WebSocket Server Testcase 9.3.5
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_4mb_Frames_16kb() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        ByteBuffer payload = newMultiFrameMessage(send, OpCode.TEXT, 4 * MBYTE, 16 * KBYTE);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(payload));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send 4MB text message in multiple frames.
     * <p>
     * From Autobahn WebSocket Server Testcase 9.3.3
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_4mb_Frames_1kb() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        ByteBuffer payload = newMultiFrameMessage(send, OpCode.TEXT, 4 * MBYTE, 1 * KBYTE);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(payload));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send 4MB text message in multiple frames.
     * <p>
     * From Autobahn WebSocket Server Testcase 9.3.8
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_4mb_Frames_1mb() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        ByteBuffer payload = newMultiFrameMessage(send, OpCode.TEXT, 4 * MBYTE, 1 * MBYTE);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(payload));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send 4MB text message in multiple frames.
     * <p>
     * From Autobahn WebSocket Server Testcase 9.3.2
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_4mb_Frames_256b() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        ByteBuffer payload = newMultiFrameMessage(send, OpCode.TEXT, 4 * MBYTE, 256);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(payload));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send 4MB text message in multiple frames.
     * <p>
     * From Autobahn WebSocket Server Testcase 9.3.7
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_4mb_Frames_256kb() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        ByteBuffer payload = newMultiFrameMessage(send, OpCode.TEXT, 4 * MBYTE, 256 * KBYTE);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(payload));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send 4MB text message in multiple frames.
     * <p>
     * From Autobahn WebSocket Server Testcase 9.3.4
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_4mb_Frames_4kb() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        ByteBuffer payload = newMultiFrameMessage(send, OpCode.TEXT, 4 * MBYTE, 4 * KBYTE);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(payload));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send 4MB text message in multiple frames.
     * <p>
     * From Autobahn WebSocket Server Testcase 9.3.9
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_4mb_Frames_4mb() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        ByteBuffer payload = newMultiFrameMessage(send, OpCode.TEXT, 4 * MBYTE, 4 * MBYTE);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(payload));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send 4MB text message in multiple frames.
     * <p>
     * From Autobahn WebSocket Server Testcase 9.3.1
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_4mb_Frames_64b() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        ByteBuffer payload = newMultiFrameMessage(send, OpCode.TEXT, 4*MBYTE, 64);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(payload));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Send 4MB text message in multiple frames.
     * <p>
     * From Autobahn WebSocket Server Testcase 9.3.6
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_4mb_Frames_64kb() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        ByteBuffer payload = newMultiFrameMessage(send, OpCode.TEXT, 4 * MBYTE, 64 * KBYTE);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(payload));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Echo 4MB text message (1 frame)
     * <p>
     * From Autobahn WebSocket Server Testcase 9.1.4
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_4mb_SingleFrame() throws Exception
    {
        byte utf[] = new byte[4 * MBYTE];
        Arrays.fill(utf,(byte)'y');
        ByteBuffer buf = ByteBuffer.wrap(utf);
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(DataUtils.copyOf(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Echo 64KB text message (1 frame)
     * <p>
     * From Autobahn WebSocket Server Testcase 9.1.1
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_64k_SingleFrame() throws Exception
    {
        byte utf[] = new byte[64 * KBYTE];
        Arrays.fill(utf,(byte)'y');
        String msg = StringUtil.toUTF8String(utf,0,utf.length);
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(msg));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(msg));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Echo 8MB text message (1 frame)
     * <p>
     * From Autobahn WebSocket Server Testcase 9.1.5
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_8mb_SingleFrame() throws Exception
    {
        byte utf[] = new byte[8 * MBYTE];
        Arrays.fill(utf,(byte)'y');
        ByteBuffer buf = ByteBuffer.wrap(utf);
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(buf));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(DataUtils.copyOf(buf)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try(LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * invalid utf8 text message, many fragments (1 byte each)
     * <p>
     * From Autobahn WebSocket Server Testcase 6.3.2
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_BadUtf8_Bulk() throws Exception
    {
        byte invalid[] = Hex.asByteArray("CEBAE1BDB9CF83CEBCCEB5EDA080656469746564");
        
        List<WebSocketFrame> send = new ArrayList<>();
        fragmentText(send, invalid);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.BAD_PAYLOAD).asFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * invalid text message, 1 frame/fragment (slowly, and split within code points)
     * <p>
     * From Autobahn WebSocket Server Testcase 6.4.3
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_BadUtf8_ByteWise() throws Exception
    {
        // Disable Long Stacks from Parser (we know this test will throw an exception)
        try (StacklessLogging ignored = new StacklessLogging(Parser.class))
        {
            ByteBuffer payload = ByteBuffer.allocate(64);
            BufferUtil.clearToFill(payload);
            payload.put(TypeUtil.fromHexString("cebae1bdb9cf83cebcceb5")); // good
            payload.put(TypeUtil.fromHexString("f4908080")); // INVALID
            payload.put(TypeUtil.fromHexString("656469746564")); // good
            BufferUtil.flipToFlush(payload, 0);
            
            List<WebSocketFrame> send = new ArrayList<>();
            send.add(new TextFrame().setPayload(payload));
            send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
            
            List<WebSocketFrame> expect = new ArrayList<>();
            expect.add(new CloseInfo(StatusCode.BAD_PAYLOAD).asFrame());
    
            try (LocalFuzzer session = server.newLocalFuzzer())
            {
                ByteBuffer net = session.asNetworkBuffer(send);
                
                int splits[] = {17, 21, net.limit()};
                
                ByteBuffer part1 = net.slice(); // Header + good UTF
                part1.limit(splits[0]);
                ByteBuffer part2 = net.slice(); // invalid UTF
                part2.position(splits[0]);
                part2.limit(splits[1]);
                ByteBuffer part3 = net.slice(); // good UTF
                part3.position(splits[1]);
                part3.limit(splits[2]);
                
                session.send(part1); // the header + good utf
                TimeUnit.MILLISECONDS.sleep(500);
                session.send(part2); // the bad UTF
                TimeUnit.MILLISECONDS.sleep(500);
                session.send(part3); // the rest (shouldn't work)
                session.eof();
                
                session.expect(expect);
            }
        }
    }
    
    /**
     * invalid text message, 3 fragments.
     * <p>
     * fragment #1 and fragment #3 are both valid in themselves.
     * </p>
     * <p>
     * fragment #2 contains the invalid utf8 code point.
     * </p>
     * <p>
     * From Autobahn WebSocket Server Testcase 6.4.1
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_BadUtf8_FrameWise() throws Exception
    {
        byte part1[] = StringUtil.getUtf8Bytes("\u03BA\u1F79\u03C3\u03BC\u03B5");
        byte part2[] = Hex.asByteArray("F4908080"); // invalid
        byte part3[] = StringUtil.getUtf8Bytes("edited");
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(ByteBuffer.wrap(part1)).setFin(false));
        send.add(new ContinuationFrame().setPayload(ByteBuffer.wrap(part2)).setFin(false));
        send.add(new ContinuationFrame().setPayload(ByteBuffer.wrap(part3)).setFin(true));
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.BAD_PAYLOAD).asFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendFrames(send);
            session.expect(expect);
        }
    }
    
    /**
     * invalid text message, 3 fragments.
     * <p>
     * fragment #1 is valid and ends in the middle of an incomplete code point.
     * </p>
     * <p>
     * fragment #2 finishes the UTF8 code point but it is invalid
     * </p>
     * <p>
     * fragment #3 contains the remainder of the message.
     * </p>
     * <p>
     * From Autobahn WebSocket Server Testcase 6.4.2
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_BadUtf8_OnCompleteCodePoint() throws Exception
    {
        byte part1[] = Hex.asByteArray("CEBAE1BDB9CF83CEBCCEB5F4"); // split code point
        byte part2[] = Hex.asByteArray("90"); // continue code point & invalid
        byte part3[] = Hex.asByteArray("8080656469746564"); // continue code point & finish
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(ByteBuffer.wrap(part1)).setFin(false));
        send.add(new ContinuationFrame().setPayload(ByteBuffer.wrap(part2)).setFin(false));
        send.add(new ContinuationFrame().setPayload(ByteBuffer.wrap(part3)).setFin(true));
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.BAD_PAYLOAD).asFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendFrames(send);
            session.expect(expect);
        }
    }
    
    /**
     * invalid text message, 1 frame/fragment (slowly, and split within code points)
     * <p>
     * From Autobahn WebSocket Server Testcase 6.4.4
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_BadUtf8_SegmentWise() throws Exception
    {
        byte invalid[] = Hex.asByteArray("CEBAE1BDB9CF83CEBCCEB5F49080808080656469746564");
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(ByteBuffer.wrap(invalid)));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new CloseInfo(StatusCode.BAD_PAYLOAD).asFrame());
    
        try (StacklessLogging ignored = new StacklessLogging(Parser.class);
             LocalFuzzer session = server.newLocalFuzzer())
        {
            ByteBuffer net = session.asNetworkBuffer(send);
            session.send(net, 6);
            session.send(net, 11);
            session.send(net, 1);
            session.send(net, 100); // the rest
            
            session.expect(expect);
        }
    }
    
    /**
     * text message, small length, 3 fragments (only middle frame has payload)
     * <p>
     * From Autobahn WebSocket Server Testcase 6.1.3
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_ContinuationWithPayload_Continuation() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setFin(false));
        send.add(new ContinuationFrame().setPayload("middle").setFin(false));
        send.add(new ContinuationFrame().setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload("middle"));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * text message, 0 length, 3 fragments
     * <p>
     * From Autobahn WebSocket Server Testcase 6.1.2
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_Continuation_Continuation_AllEmpty() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setFin(false));
        send.add(new ContinuationFrame().setFin(false));
        send.add(new ContinuationFrame().setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame());
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * text message, 1 frame, 0 length
     * <p>
     * From Autobahn WebSocket Server Testcase 6.1.1
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_Empty() throws Exception
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame());
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame());
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * valid utf8 text message, many fragments (1 byte each)
     * <p>
     * From Autobahn WebSocket Server Testcase 6.2.4
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_Utf8ContinuationsNotOnCodePoints() throws Exception
    {
        byte msg[] = Hex.asByteArray("CEBAE1BDB9CF83CEBCCEB5");
        
        List<WebSocketFrame> send = new ArrayList<>();
        fragmentText(send, msg);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(ByteBuffer.wrap(msg)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * valid utf8 text message, many fragments (1 byte each)
     * <p>
     * From Autobahn WebSocket Server Testcase 6.2.3
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_Utf8ContinuationsNotOnCodePoints_Hello() throws Exception
    {
        String utf8 = "Hello-\uC2B5@\uC39F\uC3A4\uC3BC\uC3A0\uC3A1-UTF-8!!";
        byte msg[] = StringUtil.getUtf8Bytes(utf8);
        
        List<WebSocketFrame> send = new ArrayList<>();
        fragmentText(send, msg);
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        expect.add(new TextFrame().setPayload(ByteBuffer.wrap(msg)));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * valid utf8 text message, 2 fragments (on UTF8 code point boundary)
     * <p>
     * From Autobahn WebSocket Server Testcase 6.2.2
     * </p>
     * @throws Exception on test failure
     */
    @Test
    public void testText_Utf8SplitOnCodePointBoundary() throws Exception
    {
        String utf1 = "Hello-\uC2B5@\uC39F\uC3A4";
        String utf2 = "\uC3BC\uC3A0\uC3A1-UTF-8!!";
        
        ByteBuffer b1 = ByteBuffer.wrap(StringUtil.getUtf8Bytes(utf1));
        ByteBuffer b2 = ByteBuffer.wrap(StringUtil.getUtf8Bytes(utf2));
        
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload(b1).setFin(false));
        send.add(new ContinuationFrame().setPayload(b2).setFin(true));
        send.add(new CloseInfo(StatusCode.NORMAL).asFrame());
        
        List<WebSocketFrame> expect = new ArrayList<>();
        ByteBuffer e1 = ByteBuffer.allocate(100);
        e1.put(StringUtil.getUtf8Bytes(utf1));
        e1.put(StringUtil.getUtf8Bytes(utf2));
        e1.flip();
        expect.add(new TextFrame().setPayload(e1));
        expect.add(new CloseInfo(StatusCode.NORMAL).asFrame());
    
        try (LocalFuzzer session = server.newLocalFuzzer())
        {
            session.sendBulk(send);
            session.expect(expect);
        }
    }
    
    /**
     * Split a message byte array into a series of fragments (frames + continuations) of 1 byte message contents each.
     *
     * @param frames the frames
     * @param msg the message
     */
    private void fragmentText(List<WebSocketFrame> frames, byte msg[])
    {
        int len = msg.length;
        boolean continuation = false;
        byte mini[];
        for (int i = 0; i < len; i++)
        {
            DataFrame frame;
            if (continuation)
            {
                frame = new ContinuationFrame();
            }
            else
            {
                frame = new TextFrame();
            }
            mini = new byte[1];
            mini[0] = msg[i];
            frame.setPayload(ByteBuffer.wrap(mini));
            boolean isLast = (i >= (len - 1));
            frame.setFin(isLast);
            frames.add(frame);
            continuation = true;
        }
    }
}
