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

package org.eclipse.jetty.websocket.core.parser;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.MessageTooLargeException;
import org.eclipse.jetty.websocket.core.ProtocolException;
import org.eclipse.jetty.websocket.core.StatusCode;
import org.eclipse.jetty.websocket.core.WebSocketBehavior;
import org.eclipse.jetty.websocket.core.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.frames.BinaryFrame;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;
import org.eclipse.jetty.websocket.core.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.core.frames.DataFrame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.frames.PingFrame;
import org.eclipse.jetty.websocket.core.frames.PongFrame;
import org.eclipse.jetty.websocket.core.frames.TextFrame;
import org.eclipse.jetty.websocket.core.frames.WebSocketFrame;
import org.eclipse.jetty.websocket.core.generator.Generator;
import org.eclipse.jetty.websocket.core.generator.UnitGenerator;
import org.eclipse.jetty.websocket.core.util.MaskedByteBuffer;
import org.hamcrest.CoreMatchers;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class ParserTest
{
    @Rule
    public ExpectedException expectedException = ExpectedException.none();
    
    private ParserCapture parse(WebSocketPolicy policy, ByteBuffer buffer)
    {
        ParserCapture capture = new ParserCapture();
        Parser parser = new Parser(policy, new MappedByteBufferPool(), capture);
        parser.parse(buffer);
        return capture;
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 1.2.2
     */
    @Test
    public void testParse_Binary_125BytePayload() throws InterruptedException
    {
        int length = 125;
        
        ByteBuffer expected = ByteBuffer.allocate(length + 5);
        
        expected.put(new byte[]
                { (byte)0x82 });
        byte b = 0x00; // no masking
        b |= length & 0x7F;
        expected.put(b);
        
        for ( int i = 0 ; i < length ; ++i )
        {
            expected.put("*".getBytes());
        }
        
        expected.flip();
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, expected);
        
        BinaryFrame pActual = (BinaryFrame) capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("BinaryFrame.payloadLength",pActual.getPayloadLength(),is(length));
    }

    /**
     * From Autobahn WebSocket Server Testcase 1.2.3
     */
    @Test
    public void testParse_Binary_126BytePayload() throws InterruptedException
    {
        int length = 126;
        
        ByteBuffer expected = ByteBuffer.allocate(length + 5);
        
        expected.put(new byte[]
                { (byte)0x82 });
        byte b = 0x00; // no masking
        b |= length & 0x7E;
        expected.put(b);
        expected.putShort((short)length);
        
        for ( int i = 0 ; i < length ; ++i )
        {
            expected.put("*".getBytes());
        }
        
        expected.flip();
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, expected);
        
        capture.assertHasFrame(OpCode.BINARY,1);
        
        Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("BinaryFrame.payloadLength",pActual.getPayloadLength(),is(length));
        // Assert.assertEquals("BinaryFrame.payload",length,pActual.getPayloadData().length);
    }

    /**
     * From Autobahn WebSocket Server Testcase 1.2.4
     */
    @Test
    public void testParse_Binary_127BytePayload() throws InterruptedException
    {
        int length = 127;
        
        ByteBuffer expected = ByteBuffer.allocate(length + 5);
        
        expected.put(new byte[]
                { (byte)0x82 });
        byte b = 0x00; // no masking
        b |= length & 0x7E;
        expected.put(b);
        expected.putShort((short)length);
        
        for ( int i = 0 ; i < length ; ++i )
        {
            expected.put("*".getBytes());
        }
        
        expected.flip();
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, expected);
        
        BinaryFrame pActual = (BinaryFrame) capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("BinaryFrame.payloadLength",pActual.getPayloadLength(),is(length));
        // .assertEquals("BinaryFrame.payload",length,pActual.getPayloadData().length);
    }

    /**
     * From Autobahn WebSocket Server Testcase 1.2.5
     */
    @Test
    public void testParse_Binary_128BytePayload() throws InterruptedException
    {
        int length = 128;
        
        ByteBuffer expected = ByteBuffer.allocate(length + 5);
        
        expected.put(new byte[]
                { (byte)0x82 });
        byte b = 0x00; // no masking
        b |= 0x7E;
        expected.put(b);
        expected.putShort((short)length);
        
        for ( int i = 0 ; i < length ; ++i )
        {
            expected.put("*".getBytes());
        }
        
        expected.flip();
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, expected);
        
        capture.assertHasFrame(OpCode.BINARY,1);
        
        Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("BinaryFrame.payloadLength",pActual.getPayloadLength(),is(length));
    }

    /**
     * From Autobahn WebSocket Server Testcase 1.2.6
     */
    @Test
    public void testParse_Binary_65535BytePayload() throws InterruptedException
    {
        int length = 65535;
        
        ByteBuffer expected = ByteBuffer.allocate(length + 5);
        
        expected.put(new byte[]
                { (byte)0x82 });
        byte b = 0x00; // no masking
        b |= 0x7E;
        expected.put(b);
        expected.put(new byte[]{ (byte)0xff, (byte)0xff});
        
        for ( int i = 0 ; i < length ; ++i )
        {
            expected.put("*".getBytes());
        }
        
        expected.flip();
        
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        policy.setMaxBinaryMessageSize(length);
        ParserCapture capture = parse(policy, expected);
        
        capture.assertHasFrame(OpCode.BINARY,1);
        
        Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("BinaryFrame.payloadLength",pActual.getPayloadLength(),is(length));
    }

    /**
     * From Autobahn WebSocket Server Testcase 1.2.7
     */
    @Test
    public void testParse_Binary_65536BytePayload() throws InterruptedException
    {
        int length = 65536;
        
        ByteBuffer expected = ByteBuffer.allocate(length + 11);
        
        expected.put(new byte[]
                { (byte)0x82 });
        byte b = 0x00; // no masking
        b |= 0x7F;
        expected.put(b);
        expected.put(new byte[]{0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00});
        
        for ( int i = 0 ; i < length ; ++i )
        {
            expected.put("*".getBytes());
        }
        
        expected.flip();
        
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        policy.setMaxBinaryMessageSize(length);
        ParserCapture capture = parse(policy, expected);
    
        capture.assertHasFrame(OpCode.BINARY,1);
        
        Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("BinaryFrame.payloadLength",pActual.getPayloadLength(),is(length));
    }

    /**
     * From Autobahn WebSocket Server Testcase 1.2.1
     */
    @Test
    public void testParse_Binary_Empty() throws InterruptedException
    {
        
        ByteBuffer expected = ByteBuffer.allocate(5);
        
        expected.put(new byte[]
                { (byte)0x82, (byte)0x00 });
        
        expected.flip();
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, expected);
        
        capture.assertHasFrame(OpCode.BINARY,1);
        
        Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("BinaryFrame.payloadLength",pActual.getPayloadLength(),is(0));
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 7.3.2
     */
    @Test
    public void testParse_Close_1BytePayload()
    {
        ByteBuffer expected = Hex.asByteBuffer("880100");
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        expectedException.expect(ProtocolException.class);
        expectedException.expectMessage(CoreMatchers.containsString("Invalid close frame payload length"));
        parse(policy, expected);
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 7.3.1
     */
    @Test
    public void testParse_Close_Empty() throws InterruptedException
    {
        ByteBuffer expected = ByteBuffer.allocate(5);
        
        expected.put(new byte[]
                { (byte)0x88, (byte)0x00 });
        
        expected.flip();
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, expected);
        
        capture.assertHasFrame(OpCode.CLOSE,1);
        
        Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("CloseFrame.payloadLength",pActual.getPayloadLength(),is(0));
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 7.4.6
     */
    @Test
    public void testParse_Close_WithInvalidStatusReason()
    {
        byte[] messageBytes = new byte[124];
        Arrays.fill(messageBytes,(byte)'*');
        
        ByteBuffer expected = ByteBuffer.allocate(256);
        
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
        expected.putChar((char)(messageBytes.length + 2));
        
        // payload
        expected.putShort((short)1000); // status code
        expected.put(messageBytes); // reason
        
        expected.flip();
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        expectedException.expect(ProtocolException.class);
        expectedException.expectMessage(CoreMatchers.containsString("Invalid control frame payload length"));
        parse(policy, expected);
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 7.3.3
     */
    @Test
    public void testParse_Close_WithStatus() throws InterruptedException
    {
        ByteBuffer expected = ByteBuffer.allocate(5);
        
        expected.put(new byte[]
                { (byte)0x88, (byte)0x02, 0x03, (byte)0xe8  });
        
        expected.flip();
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, expected);
        
        capture.assertHasFrame(OpCode.CLOSE,1);
        
        Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("CloseFrame.payloadLength",pActual.getPayloadLength(),is(2));
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 7.3.5
     */
    @Test
    public void testParse_Close_WithStatusMaxReason() throws InterruptedException
    {
        StringBuilder message = new StringBuilder();
        for ( int i = 0 ; i < 123 ; ++i )
        {
            message.append("*");
        }
        
        byte[] messageBytes = message.toString().getBytes(StandardCharsets.UTF_8);
        
        ByteBuffer expected = ByteBuffer.allocate(132);
        
        expected.put(new byte[]
                { (byte)0x88 });
        byte b = 0x00; // no masking
        
        b |= (messageBytes.length + 2) & 0x7F;
        expected.put(b);
        expected.putShort((short)1000);
        
        expected.put(messageBytes);
        expected.flip();
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, expected);
        
        capture.assertHasFrame(OpCode.CLOSE,1);
        
        Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("CloseFrame.payloadLength",pActual.getPayloadLength(),is(125));
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 7.3.4
     */
    @Test
    public void testParse_Close_WithStatusReason() throws InterruptedException
    {
        String message = "bad cough";
        byte[] messageBytes = message.getBytes();
        
        ByteBuffer expected = ByteBuffer.allocate(32);
        
        expected.put(new byte[]
                { (byte)0x88 });
        byte b = 0x00; // no masking
        b |= (messageBytes.length + 2) & 0x7F;
        expected.put(b);
        expected.putShort((short)1000); // status code
        expected.put(messageBytes); // status reason
        expected.flip();
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, expected);
        
        capture.assertHasFrame(OpCode.CLOSE,1);
        
        Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("CloseFrame.payloadLength",pActual.getPayloadLength(),is(messageBytes.length + 2));
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 5.15
     * <p>
     * A normal 2 fragment text text message, followed by another continuation.
     * </p>
     */
    @Test
    public void testParse_Continuation_BadFinState()
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("fragment1").setFin(false));
        send.add(new ContinuationFrame().setPayload("fragment2").setFin(true));
        send.add(new ContinuationFrame().setPayload("fragment3").setFin(false)); // bad frame
        send.add(new TextFrame().setPayload("fragment4").setFin(true));
        send.add(new CloseFrame().setPayload(StatusCode.NORMAL));
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        ByteBuffer completeBuf = new UnitGenerator(policy).asBuffer(send);
        
        expectedException.expect(ProtocolException.class);
        parse(policy, completeBuf);
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 6.2.3
     * <p>
     * Lots of small 1 byte UTF8 Text frames, representing 1 overall text message.
     * </p>
     */
    @Test
    public void testParse_Continuation_ManySmall()
    {
        String utf8 = "Hello-\uC2B5@\uC39F\uC3A4\uC3BC\uC3A0\uC3A1-UTF-8!!";
        byte msg[] = StringUtil.getUtf8Bytes(utf8);

        List<WebSocketFrame> send = new ArrayList<>();
        int textCount = 0;
        int continuationCount = 0;
        int len = msg.length;
        boolean continuation = false;
        byte mini[];
        for (int i = 0; i < len; i++)
        {
            DataFrame frame;
            if (continuation)
            {
                frame = new ContinuationFrame();
                continuationCount++;
            }
            else
            {
                frame = new TextFrame();
                textCount++;
            }
            mini = new byte[1];
            mini[0] = msg[i];
            frame.setPayload(ByteBuffer.wrap(mini));
            boolean isLast = (i >= (len - 1));
            frame.setFin(isLast);
            send.add(frame);
            continuation = true;
        }
        send.add(new CloseFrame().setPayload(StatusCode.NORMAL));
    
        WebSocketPolicy serverPolicy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        ByteBuffer completeBuf = new UnitGenerator(serverPolicy).asBuffer(send);
    
        WebSocketPolicy clientPolicy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(clientPolicy, completeBuf);
    
        capture.assertHasFrame(OpCode.TEXT,textCount);
        capture.assertHasFrame(OpCode.CONTINUATION,continuationCount);
        capture.assertHasFrame(OpCode.CLOSE,1);
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 5.19
     * <p>
     * text message, send in 5 frames/fragments, with 2 pings in the mix.
     * </p>
     */
    @Test
    public void testParse_Interleaved_PingFrames()
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("f1").setFin(false));
        send.add(new ContinuationFrame().setPayload(",f2").setFin(false));
        send.add(new PingFrame().setPayload("ping-1"));
        send.add(new ContinuationFrame().setPayload(",f3").setFin(false));
        send.add(new ContinuationFrame().setPayload(",f4").setFin(false));
        send.add(new PingFrame().setPayload("ping-2"));
        send.add(new ContinuationFrame().setPayload(",f5").setFin(true));
        send.add(new CloseFrame().setPayload(StatusCode.NORMAL));
    
        WebSocketPolicy serverPolicy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        ByteBuffer completeBuf = new UnitGenerator(serverPolicy).asBuffer(send);
    
        WebSocketPolicy clientPolicy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(clientPolicy, completeBuf);
    
    
        capture.assertHasFrame(OpCode.TEXT,1);
        capture.assertHasFrame(OpCode.CONTINUATION,4);
        capture.assertHasFrame(OpCode.CLOSE,1);
        capture.assertHasFrame(OpCode.PING,2);
    }
    
    @Test
    public void testParse_Nothing()
    {
        ByteBuffer buf = ByteBuffer.allocate(16);
        // Put nothing in the buffer.
        buf.flip();
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        ParserCapture capture = parse(policy, buf);
    
        assertThat("Frame Count",capture.framesQueue.size(),is(0));
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 4.2.1
     */
    @Test
    public void testParse_OpCode11() throws Exception
    {
        ByteBuffer expected = ByteBuffer.allocate(32);
        
        expected.put(new byte[] { (byte)0x8b, 0x00 });
        
        expected.flip();
        
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
    
        expectedException.expect(ProtocolException.class);
        expectedException.expectMessage(containsString("Unknown opcode: 11"));
        parse(policy, expected);
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 4.2.2
     */
    @Test
    public void testParse_OpCode12() throws Exception
    {
        ByteBuffer expected = ByteBuffer.allocate(32);
        
        expected.put(new byte[] { (byte)0x8c, 0x01, 0x00 });
        
        expected.flip();
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        
        expectedException.expect(ProtocolException.class);
        expectedException.expectMessage(containsString("Unknown opcode: 12"));
        parse(policy, expected);
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 4.1.1
     */
    @Test
    public void testParse_OpCode3() throws Exception
    {
        ByteBuffer expected = ByteBuffer.allocate(32);
        
        expected.put(new byte[] { (byte)0x83, 0x00 });
        
        expected.flip();
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        
        expectedException.expect(ProtocolException.class);
        expectedException.expectMessage(containsString("Unknown opcode: 3"));
        parse(policy, expected);
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 4.1.2
     */
    @Test
    public void testParse_OpCode4() throws Exception
    {
        ByteBuffer expected = ByteBuffer.allocate(32);
        
        expected.put(new byte[] { (byte)0x84, 0x01, 0x00 });
        
        expected.flip();
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        
        expectedException.expect(ProtocolException.class);
        expectedException.expectMessage(containsString("Unknown opcode: 4"));
        parse(policy, expected);
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 2.4
     */
    @Test
    public void testParse_Ping_125BytePayload() throws InterruptedException
    {
        byte[] bytes = new byte[125];
        
        for ( int i = 0 ; i < bytes.length ; ++i )
        {
            bytes[i] = Integer.valueOf(Integer.toOctalString(i)).byteValue();
        }
        
        ByteBuffer expected = ByteBuffer.allocate(bytes.length + 32);
        
        expected.put(new byte[]
                { (byte)0x89 });
        
        byte b = 0x00; // no masking
        b |= bytes.length & 0x7F;
        expected.put(b);
        expected.put(bytes);
        
        expected.flip();
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, expected);
        
        capture.assertHasFrame(OpCode.PING,1);
        
        Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("PingFrame.payloadLength",pActual.getPayloadLength(),is(bytes.length));
        Assert.assertEquals("PingFrame.payload",bytes.length,pActual.getPayloadLength());
    }
    
    @Test
    public void testParse_Ping_Basic() throws InterruptedException
    {
        ByteBuffer buf = ByteBuffer.allocate(16);
        BufferUtil.clearToFill(buf);
        buf.put(new byte[]
                { (byte)0x89, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f });
        BufferUtil.flipToFlush(buf,0);
        
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, buf);
        
        capture.assertHasFrame(OpCode.PING,1);
        PingFrame ping = (PingFrame)capture.framesQueue.poll(1, TimeUnit.SECONDS);
        
        String actual = BufferUtil.toUTF8String(ping.getPayload());
        Assert.assertThat("PingFrame.payload",actual,is("Hello"));
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 2.3
     */
    @Test
    public void testParse_Ping_BinaryPayload() throws InterruptedException
    {
        byte[] bytes = new byte[] { 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 };
        
        ByteBuffer expected = ByteBuffer.allocate(32);
        
        expected.put(new byte[]
                { (byte)0x89 });
        
        byte b = 0x00; // no masking
        b |= bytes.length & 0x7F;
        expected.put(b);
        expected.put(bytes);
        
        expected.flip();
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, expected);
        
        capture.assertHasFrame(OpCode.PING,1);
        
        Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("PingFrame.payloadLength",pActual.getPayloadLength(),is(bytes.length));
        Assert.assertEquals("PingFrame.payload",bytes.length,pActual.getPayloadLength());
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 2.1
     */
    @Test
    public void testParse_Ping_Empty() throws InterruptedException
    {
        ByteBuffer expected = ByteBuffer.allocate(5);
        
        expected.put(new byte[]
                { (byte)0x89, (byte)0x00 });
        
        expected.flip();
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, expected);
        
        capture.assertHasFrame(OpCode.PING,1);
        
        Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("PingFrame.payloadLength",pActual.getPayloadLength(),is(0));
        Assert.assertEquals("PingFrame.payload",0,pActual.getPayloadLength());
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 2.2
     */
    @Test
    public void testParse_Ping_HelloPayload() throws InterruptedException
    {
        String message = "Hello, world!";
        byte[] messageBytes = message.getBytes();
        
        ByteBuffer expected = ByteBuffer.allocate(32);
        
        expected.put(new byte[]
                { (byte)0x89 });
        
        byte b = 0x00; // no masking
        b |= messageBytes.length & 0x7F;
        expected.put(b);
        expected.put(messageBytes);
        
        expected.flip();
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, expected);
        
        capture.assertHasFrame(OpCode.PING,1);
        
        Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("PingFrame.payloadLength",pActual.getPayloadLength(),is(message.length()));
        Assert.assertEquals("PingFrame.payload",message.length(),pActual.getPayloadLength());
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 2.5
     */
    @Test
    public void testParse_Ping_OverSizedPayload()
    {
        byte[] bytes = new byte[126];
        Arrays.fill(bytes,(byte)0x00);
        
        ByteBuffer expected = ByteBuffer.allocate(bytes.length + Generator.MAX_HEADER_LENGTH);
        
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
        expected.putChar((char)bytes.length);
        
        // payload
        expected.put(bytes);
        
        expected.flip();
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        
        expectedException.expect(ProtocolException.class);
        parse(policy, expected);
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 5.6
     * <p>
     * pong, then text, then close frames.
     * </p>
     */
    @Test
    public void testParse_PongTextClose()
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new PongFrame().setPayload("ping"));
        send.add(new TextFrame().setPayload("hello, world"));
        send.add(new CloseFrame().setPayload(StatusCode.NORMAL));

        WebSocketPolicy serverPolicy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        ByteBuffer completeBuf = new UnitGenerator(serverPolicy).asBuffer(send);
        
        WebSocketPolicy clientPolicy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(clientPolicy, completeBuf);
    
        capture.assertHasFrame(OpCode.TEXT,1);
        capture.assertHasFrame(OpCode.CLOSE,1);
        capture.assertHasFrame(OpCode.PONG,1);
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 2.5
     */
    @Test
    public void testParse_Pong_OverSizedPayload()
    {
        byte[] bytes = new byte[126];
        Arrays.fill(bytes,(byte)0x00);
        
        ByteBuffer expected = ByteBuffer.allocate(bytes.length + Generator.MAX_HEADER_LENGTH);
        
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
        expected.putChar((char)bytes.length);
        
        // payload
        expected.put(bytes);
        
        expected.flip();
        
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        
        expectedException.expect(ProtocolException.class);
        parse(policy, expected);
    }
    
    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testParse_RFC6455_FragmentedUnmaskedTextMessage() throws InterruptedException
    {
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = new ParserCapture();
        Parser parser = new Parser(policy, new MappedByteBufferPool(), capture);
        
        ByteBuffer buf = ByteBuffer.allocate(16);
        BufferUtil.clearToFill(buf);
        
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // A fragmented unmasked text message (part 1 of 2 "Hel")
        buf.put(new byte[]
                { (byte)0x01, (byte)0x03, 0x48, (byte)0x65, 0x6c });
        
        // Parse #1
        BufferUtil.flipToFlush(buf,0);
        parser.parse(buf);
        
        // part 2 of 2 "lo" (A continuation frame of the prior text message)
        BufferUtil.flipToFill(buf);
        buf.put(new byte[]
                { (byte)0x80, 0x02, 0x6c, 0x6f });
        
        // Parse #2
        BufferUtil.flipToFlush(buf,0);
        parser.parse(buf);
        
        capture.assertHasFrame(OpCode.TEXT,1);
        capture.assertHasFrame(OpCode.CONTINUATION,1);
    
        WebSocketFrame txt = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        String actual = BufferUtil.toUTF8String(txt.getPayload());
        assertThat("TextFrame[0].data", actual, is("Hel"));
        txt = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        actual = BufferUtil.toUTF8String(txt.getPayload());
        assertThat("TextFrame[1].data", actual, is("lo"));
    }
    
    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testParse_RFC6455_SingleMaskedPongRequest() throws InterruptedException
    {
        ByteBuffer buf = ByteBuffer.allocate(16);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // Unmasked Pong request
        buf.put(new byte[]
                { (byte)0x8a, (byte)0x85, 0x37, (byte)0xfa, 0x21, 0x3d, 0x7f, (byte)0x9f, 0x4d, 0x51, 0x58 });
        buf.flip();
        
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        ParserCapture capture = parse(policy, buf);
        
        capture.assertHasFrame(OpCode.PONG,1);
        
        WebSocketFrame pong = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        String actual = BufferUtil.toUTF8String(pong.getPayload());
        assertThat("PongFrame.payload",actual,is("Hello"));
    }
    
    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testParse_RFC6455_SingleMaskedTextMessage() throws InterruptedException
    {
        ByteBuffer buf = ByteBuffer.allocate(16);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // A single-frame masked text message
        buf.put(new byte[]
                { (byte)0x81, (byte)0x85, 0x37, (byte)0xfa, 0x21, 0x3d, 0x7f, (byte)0x9f, 0x4d, 0x51, 0x58 });
        buf.flip();
        
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        ParserCapture capture = parse(policy, buf);
        
        capture.assertHasFrame(OpCode.TEXT,1);
        
        WebSocketFrame txt = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        String actual = BufferUtil.toUTF8String(txt.getPayload());
        assertThat("TextFrame.payload",actual,is("Hello"));
    }
    
    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testParse_RFC6455_SingleUnmasked256ByteBinaryMessage() throws InterruptedException
    {
        int dataSize = 256;
        
        ByteBuffer buf = ByteBuffer.allocate(dataSize + 10);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // 256 bytes binary message in a single unmasked frame
        buf.put(new byte[]
                { (byte)0x82, 0x7E });
        buf.putShort((short)0x01_00); // 16 bit size
        for (int i = 0; i < dataSize; i++)
        {
            buf.put((byte)0x44);
        }
        buf.flip();
        
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, buf);
        
        capture.assertHasFrame(OpCode.BINARY,1);
        
        Frame bin = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        
        assertThat("BinaryFrame.payloadLength",bin.getPayloadLength(),is(dataSize));
        
        ByteBuffer data = bin.getPayload();
        assertThat("BinaryFrame.payload.length",data.remaining(),is(dataSize));
        
        for (int i = 0; i < dataSize; i++)
        {
            assertThat("BinaryFrame.payload[" + i + "]",data.get(i),is((byte)0x44));
        }
    }
    
    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testParse_RFC6455_SingleUnmasked64KByteBinaryMessage() throws InterruptedException
    {
        int dataSize = 1024 * 64;
        
        ByteBuffer buf = ByteBuffer.allocate((dataSize + 10));
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // 64 KiloByte binary message in a single unmasked frame
        buf.put(new byte[]
                { (byte)0x82, 0x7F });
        buf.putLong(dataSize); // 64bit size
        for (int i = 0; i < dataSize; i++)
        {
            buf.put((byte)0x77);
        }
        buf.flip();
        
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = new ParserCapture();
        Parser parser = new Parser(policy, new MappedByteBufferPool(),capture);
        assertThat(parser.parse(buf), is(true));
        
        capture.assertHasFrame(OpCode.BINARY,1);
        
        Frame bin = capture.framesQueue.poll(1,TimeUnit.SECONDS);
        
        assertThat("BinaryFrame.payloadLength",bin.getPayloadLength(),is(dataSize));
        ByteBuffer data = bin.getPayload();
        assertThat("BinaryFrame.payload.length",data.remaining(),is(dataSize));
        
        for (int i = 0; i < dataSize; i++)
        {
            assertThat("BinaryFrame.payload[" + i + "]",data.get(i),is((byte)0x77));
        }
    }
    
    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testParse_RFC6455_SingleUnmaskedPingRequest() throws InterruptedException
    {
        ByteBuffer buf = ByteBuffer.allocate(16);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // Unmasked Ping request
        buf.put(new byte[]
                { (byte)0x89, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f });
        buf.flip();
        
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, buf);
        
        capture.assertHasFrame(OpCode.PING,1);
        
        WebSocketFrame ping = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        String actual = BufferUtil.toUTF8String(ping.getPayload());
        assertThat("PingFrame.payload",actual,is("Hello"));
    }
    
    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testParse_RFC6455_SingleUnmaskedTextMessage() throws InterruptedException
    {
        ByteBuffer buf = ByteBuffer.allocate(16);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // A single-frame unmasked text message
        buf.put(new byte[]
                { (byte)0x81, 0x05, 0x48, 0x65, 0x6c, 0x6c, 0x6f });
        buf.flip();
        
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, buf);
        
        capture.assertHasFrame(OpCode.TEXT,1);
        
        WebSocketFrame txt = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        String actual = BufferUtil.toUTF8String(txt.getPayload());
        assertThat("TextFrame.payload",actual,is("Hello"));
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 1.1.2
     */
    @Test
    public void testParse_Text_125BytePayload() throws InterruptedException
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
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, expected);
        
        capture.assertHasFrame(OpCode.TEXT,1);
        
        Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(length));
        // Assert.assertEquals("TextFrame.payload",length,pActual.getPayloadData().length);
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 1.1.3
     */
    @Test
    public void testParse_Text_126BytePayload() throws InterruptedException
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
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, expected);
        
        capture.assertHasFrame(OpCode.TEXT,1);
        
        Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(length));
        // Assert.assertEquals("TextFrame.payload",length,pActual.getPayloadData().length);
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 1.1.4
     */
    @Test
    public void testParse_Text_127BytePayload() throws InterruptedException
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
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, expected);
        
        capture.assertHasFrame(OpCode.TEXT,1);
        
        Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(length));
        // Assert.assertEquals("TextFrame.payload",length,pActual.getPayloadData().length);
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 1.1.5
     */
    @Test
    public void testParse_Text_128BytePayload() throws InterruptedException
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
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, expected);
        
        capture.assertHasFrame(OpCode.TEXT,1);
        
        Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(length));
        // .assertEquals("TextFrame.payload",length,pActual.getPayloadData().length);
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 1.1.6
     */
    @Test
    public void testParse_Text_65535BytePayload() throws InterruptedException
    {
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
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        policy.setMaxTextMessageSize(length);
        ParserCapture capture = parse(policy, expected);
        
        capture.assertHasFrame(OpCode.TEXT,1);
        
        Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(length));
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 1.1.7
     */
    @Test
    public void testParse_Text_65536BytePayload() throws InterruptedException
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
        
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        policy.setMaxTextMessageSize(length);
        ParserCapture capture = parse(policy, expected);
        
        capture.assertHasFrame(OpCode.TEXT,1);
        
        Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(length));
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 5.18
     * <p>
     * Text message fragmented as 2 frames, both as opcode=TEXT
     * </p>
     */
    @Test
    public void testParse_Text_BadFinState()
    {
        List<WebSocketFrame> send = new ArrayList<>();
        send.add(new TextFrame().setPayload("fragment1").setFin(false));
        send.add(new TextFrame().setPayload("fragment2").setFin(true)); // bad frame, must be continuation
        send.add(new CloseFrame().setPayload(StatusCode.NORMAL));

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        ByteBuffer completeBuf = new UnitGenerator(policy).asBuffer(send);
        
        expectedException.expect(ProtocolException.class);
        parse(policy, completeBuf);
    }
    
    /**
     * From Autobahn WebSocket Server Testcase 1.1.1
     */
    @Test
    public void testParse_Text_Empty() throws InterruptedException
    {
        ByteBuffer expected = ByteBuffer.allocate(5);
        
        expected.put(new byte[]
                { (byte)0x81, (byte)0x00 });
        
        expected.flip();
    
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        ParserCapture capture = parse(policy, expected);
        
        capture.assertHasFrame(OpCode.TEXT,1);
        
        Frame pActual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("TextFrame.payloadLength",pActual.getPayloadLength(),is(0));
    }
    
    @Test
    public void testParse_Text_FrameTooLargeDueToPolicy() throws Exception
    {
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        // Artificially small buffer/payload
        policy.setInputBufferSize(1024); // read buffer
        policy.setMaxAllowedFrameSize(1024); // streaming buffer (not used in this test)
        policy.setMaxTextMessageSize(1024); // actual maximum text message size policy
        byte utf[] = new byte[2048];
        Arrays.fill(utf,(byte)'a');
        
        Assert.assertThat("Must be a medium length payload",utf.length,allOf(greaterThan(0x7E),lessThan(0xFFFF)));
        
        ByteBuffer buf = ByteBuffer.allocate(utf.length + 8);
        buf.put((byte)0x81); // text frame, fin = true
        buf.put((byte)(0x80 | 0x7E)); // 0x7E == 126 (a 2 byte payload length)
        buf.putShort((short)utf.length);
        MaskedByteBuffer.putMask(buf);
        MaskedByteBuffer.putPayload(buf,utf);
        buf.flip();
        
        expectedException.expect(MessageTooLargeException.class);
        parse(policy, buf);
    }
    
    @Test
    public void testParse_Text_LongMasked() throws Exception
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 3500; i++)
        {
            sb.append("Hell\uFF4f Big W\uFF4Frld ");
        }
        sb.append(". The end.");
        
        String expectedText = sb.toString();
        byte utf[] = expectedText.getBytes(StandardCharsets.UTF_8);
        
        Assert.assertThat("Must be a long length payload",utf.length,greaterThan(0xFFFF));
        
        ByteBuffer buf = ByteBuffer.allocate(utf.length + 32);
        buf.put((byte)0x81); // text frame, fin = true
        buf.put((byte)(0x80 | 0x7F)); // 0x7F == 127 (a 8 byte payload length)
        buf.putLong(utf.length);
        MaskedByteBuffer.putMask(buf);
        MaskedByteBuffer.putPayload(buf,utf);
        buf.flip();
        
        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        policy.setMaxTextMessageSize(100000);
        ParserCapture capture = parse(policy, buf);
        
        capture.assertHasFrame(OpCode.TEXT,1);
        WebSocketFrame txt = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("TextFrame.data",txt.getPayloadAsUTF8(),is(expectedText));
    }
    
    @Test
    public void testParse_Text_ManySmallBuffers() throws InterruptedException
    {
        // Create frames
        byte payload[] = new byte[65536];
        Arrays.fill(payload,(byte)'*');

        List<WebSocketFrame> frames = new ArrayList<>();
        TextFrame text = new TextFrame();
        text.setPayload(ByteBuffer.wrap(payload));
        text.setMask(Hex.asByteArray("11223344"));
        frames.add(text);
        frames.add(new CloseFrame().setPayload(StatusCode.NORMAL));
    
        WebSocketPolicy serverPolicy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        WebSocketPolicy clientPolicy = new WebSocketPolicy(WebSocketBehavior.CLIENT);
        
        // Build up raw (network bytes) buffer
        ByteBuffer networkBytes = new UnitGenerator(clientPolicy).asBuffer(frames);

        // Parse, in 4096 sized windows
        ParserCapture capture = new ParserCapture();
        Parser parser = new Parser(serverPolicy, new MappedByteBufferPool(),capture);

        while (networkBytes.remaining() > 0)
        {
            ByteBuffer window = networkBytes.slice();
            int windowSize = Math.min(window.remaining(),4096);
            window.limit(windowSize);
            assertThat(parser.parse(window), is(true));
            networkBytes.position(networkBytes.position() + windowSize);
        }

        assertThat("Frame Count",capture.framesQueue.size(),is(2));
        WebSocketFrame frame = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Frame[0].opcode",frame.getOpCode(),is(OpCode.TEXT));
        ByteBuffer actualPayload = frame.getPayload();
        assertThat("Frame[0].payload.length",actualPayload.remaining(),is(payload.length));
        // Should be all '*' characters (if masking is correct)
        for (int i = actualPayload.position(); i < actualPayload.remaining(); i++)
        {
            assertThat("Frame[0].payload[i]",actualPayload.get(i),is((byte)'*'));
        }
    }
    
    @Test
    public void testParse_Text_MediumMasked() throws Exception
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 14; i++)
        {
            sb.append("Hell\uFF4f Medium W\uFF4Frld ");
        }
        sb.append(". The end.");
        
        String expectedText = sb.toString();
        byte utf[] = expectedText.getBytes(StandardCharsets.UTF_8);
        
        Assert.assertThat("Must be a medium length payload",utf.length,allOf(greaterThan(0x7E),lessThan(0xFFFF)));
        
        ByteBuffer buf = ByteBuffer.allocate(utf.length + 10);
        buf.put((byte)0x81);
        buf.put((byte)(0x80 | 0x7E)); // 0x7E == 126 (a 2 byte payload length)
        buf.putShort((short)utf.length);
        MaskedByteBuffer.putMask(buf);
        MaskedByteBuffer.putPayload(buf,utf);
        buf.flip();
        
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        ParserCapture capture = parse(policy, buf);
        
        capture.assertHasFrame(OpCode.TEXT,1);
        WebSocketFrame txt = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("TextFrame.data",txt.getPayloadAsUTF8(),is(expectedText));
    }
    
    @Test
    public void testParse_Text_ShortMasked() throws Exception
    {
        String expectedText = "Hello World";
        byte utf[] = expectedText.getBytes(StandardCharsets.UTF_8);
        
        ByteBuffer buf = ByteBuffer.allocate(24);
        buf.put((byte)0x81);
        buf.put((byte)(0x80 | utf.length));
        MaskedByteBuffer.putMask(buf);
        MaskedByteBuffer.putPayload(buf,utf);
        buf.flip();
        
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        ParserCapture capture = parse(policy, buf);
        
        capture.assertHasFrame(OpCode.TEXT,1);
        WebSocketFrame txt = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("TextFrame.data",txt.getPayloadAsUTF8(),is(expectedText));
    }
    
    @Test
    public void testParse_Text_ShortMaskedFragmented() throws Exception
    {
        String part1 = "Hello ";
        String part2 = "World";
        
        byte b1[] = part1.getBytes(StandardCharsets.UTF_8);
        byte b2[] = part2.getBytes(StandardCharsets.UTF_8);
        
        ByteBuffer buf = ByteBuffer.allocate(32);
        
        // part 1
        buf.put((byte)0x01); // no fin + text
        buf.put((byte)(0x80 | b1.length));
        MaskedByteBuffer.putMask(buf);
        MaskedByteBuffer.putPayload(buf,b1);
        
        // part 2
        buf.put((byte)0x80); // fin + continuation
        buf.put((byte)(0x80 | b2.length));
        MaskedByteBuffer.putMask(buf);
        MaskedByteBuffer.putPayload(buf,b2);
        
        buf.flip();
        
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        ParserCapture capture = parse(policy, buf);
        
        capture.assertHasFrame(OpCode.TEXT,1);
        capture.assertHasFrame(OpCode.CONTINUATION,1);
        WebSocketFrame txt = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("TextFrame[0].data",txt.getPayloadAsUTF8(),is(part1));
        txt = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("TextFrame[1].data",txt.getPayloadAsUTF8(),is(part2));
    }
    
    @Test
    public void testParse_Text_ShortMaskedUtf8() throws Exception
    {
        String expectedText = "Hell\uFF4f W\uFF4Frld";
        
        byte utf[] = expectedText.getBytes(StandardCharsets.UTF_8);
        
        ByteBuffer buf = ByteBuffer.allocate(24);
        buf.put((byte)0x81);
        buf.put((byte)(0x80 | utf.length));
        MaskedByteBuffer.putMask(buf);
        MaskedByteBuffer.putPayload(buf,utf);
        buf.flip();
        
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        ParserCapture capture = parse(policy, buf);
        
        capture.assertHasFrame(OpCode.TEXT,1);
        WebSocketFrame txt = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        Assert.assertThat("TextFrame.data",txt.getPayloadAsUTF8(),is(expectedText));
    }
}
