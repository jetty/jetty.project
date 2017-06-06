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

package org.eclipse.jetty.websocket.tests;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.toolchain.test.ByteBufferAssert;
import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.Generator;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.Parser;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.frames.BinaryFrame;
import org.eclipse.jetty.websocket.common.frames.CloseFrame;
import org.eclipse.jetty.websocket.common.frames.ContinuationFrame;
import org.eclipse.jetty.websocket.common.frames.PingFrame;
import org.eclipse.jetty.websocket.common.frames.PongFrame;
import org.eclipse.jetty.websocket.common.frames.TextFrame;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

public class GeneratorTest
{
    private static final Logger LOG = Log.getLogger(GeneratorTest.WindowHelper.class);
    
    @Rule
    public ExpectedException expectedException = ExpectedException.none();
    
    private static UnitGenerator unitGenerator = new UnitGenerator(WebSocketPolicy.newServerPolicy(), true);

    /**
     * From Autobahn WebSocket Client Testcase 1.2.2
     * <p>
     *     (generates a payload-length using 1 bytes)
     * </p>
     */
    @Test
    public void testGenerate_Binary_125BytePayload()
    {
        int length = 125;
        
        ByteBuffer bb = ByteBuffer.allocate(length);
        
        for ( int i = 0 ; i < length ; ++i)
        {
            bb.put("*".getBytes());
        }
        
        bb.flip();
        
        WebSocketFrame binaryFrame = new BinaryFrame().setPayload(bb);
        
        ByteBuffer actual = unitGenerator.generate(binaryFrame);
        
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
        
        BufferUtil.flipToFlush(expected,0);
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 1.2.3
     * <p>
     *     (generates a payload-length using 2 bytes)
     * </p>
     */
    @Test
    public void testGenerate_Binary_126BytePayload()
    {
        int length = 126;
        
        ByteBuffer bb = ByteBuffer.allocate(length);
        
        for ( int i = 0 ; i < length ; ++i)
        {
            bb.put("*".getBytes());
        }
        
        bb.flip();
        
        WebSocketFrame binaryFrame = new BinaryFrame().setPayload(bb);
        
        ByteBuffer actual = unitGenerator.generate(binaryFrame);
        
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
        
        BufferUtil.flipToFlush(expected,0);
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 1.2.4
     * <p>
     *     (generates a payload-length using 2 bytes)
     * </p>
     */
    @Test
    public void testGenerate_Binary_127BytePayload()
    {
        int length = 127;
        
        ByteBuffer bb = ByteBuffer.allocate(length);
        
        for ( int i = 0 ; i < length ; ++i)
        {
            bb.put("*".getBytes());
            
        }
        
        bb.flip();
        
        WebSocketFrame binaryFrame = new BinaryFrame().setPayload(bb);
        
        ByteBuffer actual = unitGenerator.generate(binaryFrame);
        
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
        
        BufferUtil.flipToFlush(expected,0);
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }
    
    /**
     * From Autobahn WebSocket Client Testcase 1.2.5
     * <p>
     *     (generates a payload-length using 2 bytes)
     * </p>
     */
    @Test
    public void testGenerate_Binary_128BytePayload()
    {
        int length = 128;
        
        ByteBuffer bb = ByteBuffer.allocate(length);
        
        for ( int i = 0 ; i < length ; ++i)
        {
            bb.put("*".getBytes());
            
        }
        
        bb.flip();
        WebSocketFrame binaryFrame = new BinaryFrame().setPayload(bb);
        
        ByteBuffer actual = unitGenerator.generate(binaryFrame);
        
        ByteBuffer expected = ByteBuffer.allocate(length + 5);
        
        expected.put(new byte[]
                { (byte)0x82 });
        
        byte b = 0x00; // no masking
        b |= 0x7E;
        expected.put(b);
        
        expected.put((byte)(length>>8));
        expected.put((byte)(length & 0xFF));
        
        for ( int i = 0 ; i < length ; ++i )
        {
            expected.put("*".getBytes());
        }
        
        BufferUtil.flipToFlush(expected,0);
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
        
    }
    
    /**
     * From Autobahn WebSocket Client Testcase 1.2.6
     * <p>
     *     (generates a payload-length using 2 bytes)
     * </p>
     */
    @Test
    public void testGenerate_Binary_65535BytePayload()
    {
        int length = 65535;
        
        ByteBuffer bb = ByteBuffer.allocate(length);
        
        for ( int i = 0 ; i < length ; ++i)
        {
            bb.put("*".getBytes());
            
        }
        
        bb.flip();
        
        WebSocketFrame binaryFrame = new BinaryFrame().setPayload(bb);
        
        ByteBuffer actual = unitGenerator.generate(binaryFrame);
        
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
        
        BufferUtil.flipToFlush(expected,0);
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 1.2.7
     * <p>
     *     (generates a payload-length using 8 bytes)
     * </p>
     */
    @Test
    public void testGenerate_Binary_65536BytePayload()
    {
        int length = 65536;
        
        ByteBuffer bb = ByteBuffer.allocate(length);
        
        for ( int i = 0 ; i < length ; ++i)
        {
            bb.put("*".getBytes());
            
        }
        
        bb.flip();
        
        WebSocketFrame binaryFrame = new BinaryFrame().setPayload(bb);
        
        ByteBuffer actual = unitGenerator.generate(binaryFrame);
        
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
        
        BufferUtil.flipToFlush(expected,0);
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 1.2.1
     */
    @Test
    public void testGenerate_Binary_Empty()
    {
        WebSocketFrame binaryFrame = new BinaryFrame().setPayload(new byte[] {});
        
        ByteBuffer actual = unitGenerator.generate(binaryFrame);
        
        ByteBuffer expected = ByteBuffer.allocate(5);
        
        expected.put(new byte[]
                { (byte)0x82, (byte)0x00 });
        
        BufferUtil.flipToFlush(expected,0);
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }

    /**
     * From Autobahn WebSocket Client Testcase 7.3.2
     */
    @Test
    public void testGenerate_Close_1BytePayload()
    {
        CloseFrame closeFrame = new CloseFrame();
        closeFrame.setPayload(Hex.asByteBuffer("00"));
        
        expectedException.expect(ProtocolException.class);
        unitGenerator.generate(closeFrame);
    }

    @Test
    public void testGenerate_Close_CodeNoReason()
    {
        CloseInfo close = new CloseInfo(StatusCode.NORMAL);
        // 2 byte payload (2 bytes for status code)
        assertGeneratedBytes("880203E8",close.asFrame());
    }

    @Test
    public void testGenerate_Close_CodeOkReason()
    {
        CloseInfo close = new CloseInfo(StatusCode.NORMAL,"OK");
        // 4 byte payload (2 bytes for status code, 2 more for "OK")
        assertGeneratedBytes("880403E84F4B",close.asFrame());
    }

    /**
     * From Autobahn WebSocket Client Testcase 7.3.1
     */
    @Test
    public void testGenerate_Close_Empty()
    {
        // 0 byte payload (no status code)
        assertGeneratedBytes("8800",new CloseFrame());
    }

    /**
     * From Autobahn WebSocket Client Testcase 7.3.6
     */
    @Test
    public void testGenerate_Close_WithInvalidStatusReason()
    {
        StringBuilder message = new StringBuilder();
        for ( int i = 0 ; i < 124 ; ++i )
        {
            message.append("*");
        }
        
        byte[] messageBytes = message.toString().getBytes();
        
        CloseFrame closeFrame = new CloseFrame();
        
        ByteBuffer bb = ByteBuffer.allocate(CloseFrame.MAX_CONTROL_PAYLOAD + 1); // 126 which is too big for control
        
        bb.putChar((char)1000);
        bb.put(messageBytes);
        
        BufferUtil.flipToFlush(bb,0);
    
        expectedException.expect(ProtocolException.class);
        closeFrame.setPayload(bb);
    }
    
    /**
     * From Autobahn WebSocket Client Testcase 7.3.3
     */
    @Test
    public void testGenerate_Close_WithStatus()
    {
        CloseInfo close = new CloseInfo(1000);
        
        ByteBuffer actual = unitGenerator.generate(close.asFrame());
        
        ByteBuffer expected = ByteBuffer.allocate(5);
        
        expected.put(new byte[]
                { (byte)0x88, (byte)0x02, 0x03, (byte)0xe8 });
        
        expected.flip();
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }
    
    /**
     * From Autobahn WebSocket Client Testcase 7.3.5
     */
    @Test
    public void testGenerate_Close_WithStatusMaxReason()
    {
        StringBuilder message = new StringBuilder();
        for ( int i = 0 ; i < 123 ; ++i )
        {
            message.append("*");
        }
        
        CloseInfo close = new CloseInfo(1000,message.toString());
        
        ByteBuffer actual = unitGenerator.generate(close.asFrame());
        ByteBuffer expected = ByteBuffer.allocate(132);
        
        byte messageBytes[] = message.toString().getBytes(StandardCharsets.UTF_8);
        
        expected.put(new byte[]
                { (byte)0x88 });
        
        byte b = 0x00; // no masking
        b |= (messageBytes.length + 2) & 0x7F;
        expected.put(b);
        expected.putShort((short)1000);
        
        expected.put(messageBytes);
        
        expected.flip();
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }
    
    /**
     * From Autobahn WebSocket Client Testcase 7.3.4
     */
    @Test
    public void testGenerate_Close_WithStatusReason()
    {
        String message = "bad cough";
        byte[] messageBytes = message.getBytes();
        
        CloseInfo close = new CloseInfo(1000,message);
        
        ByteBuffer actual = unitGenerator.generate(close.asFrame());
        
        ByteBuffer expected = ByteBuffer.allocate(32);
        
        expected.put(new byte[]
                { (byte)0x88 });
        
        byte b = 0x00; // no masking
        b |= (message.length() + 2) & 0x7F;
        expected.put(b);
        expected.putShort((short)1000);
        expected.put(messageBytes);
        
        expected.flip();
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }
    
    /**
     * Prevent regression of masking of many packets.
     */
    @Test
    public void testGenerate_Masked_ManyFrames()
    {
        int pingCount = 2;

        // Prepare frames
        WebSocketFrame[] frames = new WebSocketFrame[pingCount + 1];
        for (int i = 0; i < pingCount; i++)
        {
            frames[i] = new PingFrame().setPayload(String.format("ping-%d",i));
        }
        frames[pingCount] = new CloseInfo(StatusCode.NORMAL).asFrame();

        // Mask All Frames
        byte maskingKey[] = Hex.asByteArray("11223344");
        for (WebSocketFrame f : frames)
        {
            f.setMask(maskingKey);
        }

        // Validate result of generation
        StringBuilder expected = new StringBuilder();
        expected.append("8986").append("11223344");
        expected.append(asMaskedHex("ping-0",maskingKey)); // ping 0
        expected.append("8986").append("11223344");
        expected.append(asMaskedHex("ping-1",maskingKey)); // ping 1
        expected.append("8882").append("11223344");
        byte closure[] = Hex.asByteArray("03E8");
        mask(closure,maskingKey);
        expected.append(Hex.asHex(closure)); // normal closure

        assertGeneratedBytes(expected,frames);
    }
    
    /**
     * From Autobahn WebSocket Client Testcase 2.4
     */
    @Test
    public void testGenerate_Ping_125BytePayload()
    {
        byte[] bytes = new byte[125];
        
        for ( int i = 0 ; i < bytes.length ; ++i )
        {
            bytes[i] = Integer.valueOf(Integer.toOctalString(i)).byteValue();
        }
        
        WebSocketFrame pingFrame = new PingFrame().setPayload(bytes);
        
        ByteBuffer actual = unitGenerator.generate(pingFrame);
        
        ByteBuffer expected = ByteBuffer.allocate(bytes.length + 32);
        
        expected.put(new byte[]
                { (byte)0x89 });
        
        byte b = 0x00; // no masking
        b |= bytes.length & 0x7F;
        expected.put(b);
        expected.put(bytes);
        
        expected.flip();
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }
    
    /**
     * From Autobahn WebSocket Client Testcase 2.3
     */
    @Test
    public void testGenerate_Ping_BinaryPaylod()
    {
        byte[] bytes = new byte[] { 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08 };
        
        PingFrame pingFrame = new PingFrame().setPayload(bytes);
        
        ByteBuffer actual = unitGenerator.generate(pingFrame);
        
        ByteBuffer expected = ByteBuffer.allocate(32);
        
        expected.put(new byte[]
                { (byte)0x89 });
        
        byte b = 0x00; // no masking
        b |= bytes.length & 0x7F;
        expected.put(b);
        expected.put(bytes);
        
        expected.flip();
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }
    
    /**
     * From Autobahn WebSocket Client Testcase 2.1
     */
    @Test
    public void testGenerate_Ping_Empty()
    {
        WebSocketFrame pingFrame = new PingFrame();
        
        ByteBuffer actual = unitGenerator.generate(pingFrame);
        
        ByteBuffer expected = ByteBuffer.allocate(5);
        
        expected.put(new byte[]
                { (byte)0x89, (byte)0x00 });
        
        expected.flip();
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }
    
    /**
     * From Autobahn WebSocket Client Testcase 2.2
     */
    @Test
    public void testGenerate_Ping_HelloPayload()
    {
        String message = "Hello, world!";
        byte[] messageBytes = StringUtil.getUtf8Bytes(message);
        
        PingFrame pingFrame = new PingFrame().setPayload(messageBytes);
        
        ByteBuffer actual = unitGenerator.generate(pingFrame);
        
        ByteBuffer expected = ByteBuffer.allocate(32);
        
        expected.put(new byte[]
                { (byte)0x89 });
        
        byte b = 0x00; // no masking
        b |= messageBytes.length & 0x7F;
        expected.put(b);
        expected.put(messageBytes);
        
        expected.flip();
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }
    
    /**
     * From Autobahn WebSocket Client Testcase 2.5
     */
    @Test
    public void testGenerate_Ping_OverSizedPayload()
    {
        byte[] bytes = new byte[126];
        Arrays.fill(bytes,(byte)0x00);
        
        expectedException.expect(WebSocketException.class);
        PingFrame pingFrame = new PingFrame();
        pingFrame.setPayload(ByteBuffer.wrap(bytes)); // should throw exception
    }
    
    /**
     * From Autobahn WebSocket Client Testcase 2.5
     */
    @Test
    public void testGenerate_Pong_OverSizedPayload()
    {
        byte[] bytes = new byte[126];
        Arrays.fill(bytes, (byte)0x00);
    
        expectedException.expect(WebSocketException.class);
        PongFrame pingFrame = new PongFrame();
        pingFrame.setPayload(ByteBuffer.wrap(bytes)); // should throw exception
    }
    
    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testGenerate_RFC6455_FragmentedUnmaskedTextMessage()
    {
        WebSocketFrame text1 = new TextFrame().setPayload("Hel").setFin(false);
        WebSocketFrame text2 = new ContinuationFrame().setPayload("lo");
        
        ByteBuffer actual1 = unitGenerator.generate(text1);
        ByteBuffer actual2 = unitGenerator.generate(text2);
        
        ByteBuffer expected1 = ByteBuffer.allocate(5);
        
        expected1.put(new byte[]
                { (byte)0x01, (byte)0x03, (byte)0x48, (byte)0x65, (byte)0x6c });
        
        ByteBuffer expected2 = ByteBuffer.allocate(4);
        
        expected2.put(new byte[]
                { (byte)0x80, (byte)0x02, (byte)0x6c, (byte)0x6f });
        
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
    public void testGenerate_RFC6455_SingleMaskedPongRequest()
    {
        PongFrame pong = new PongFrame().setPayload("Hello");
        pong.setMask(new byte[]
                { 0x37, (byte)0xfa, 0x21, 0x3d });
        
        ByteBuffer actual = unitGenerator.generate(pong);
        
        ByteBuffer expected = ByteBuffer.allocate(11);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // Unmasked Pong request
        expected.put(new byte[]
                { (byte)0x8a, (byte)0x85, 0x37, (byte)0xfa, 0x21, 0x3d, 0x7f, (byte)0x9f, 0x4d, 0x51, 0x58 });
        expected.flip(); // make readable
        
        ByteBufferAssert.assertEquals("pong buffers are not equal",expected,actual);
    }
    
    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testGenerate_RFC6455_SingleMaskedTextMessage()
    {
        WebSocketFrame text = new TextFrame().setPayload("Hello");
        text.setMask(new byte[]
                { 0x37, (byte)0xfa, 0x21, 0x3d });
        
        ByteBuffer actual = unitGenerator.generate(text);
        
        ByteBuffer expected = ByteBuffer.allocate(11);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // A single-frame masked text message
        expected.put(new byte[]
                { (byte)0x81, (byte)0x85, 0x37, (byte)0xfa, 0x21, 0x3d, 0x7f, (byte)0x9f, 0x4d, 0x51, 0x58 });
        expected.flip(); // make readable
        
        ByteBufferAssert.assertEquals("masked text buffers are not equal",expected,actual);
    }
    
    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testGenerate_RFC6455_SingleUnmasked256ByteBinaryMessage()
    {
        int dataSize = 256;
        
        BinaryFrame binary = new BinaryFrame();
        byte payload[] = new byte[dataSize];
        Arrays.fill(payload,(byte)0x44);
        binary.setPayload(ByteBuffer.wrap(payload));
        
        ByteBuffer actual = unitGenerator.generate(binary);
    
        ByteBuffer expected = ByteBuffer.allocate(dataSize + Generator.MAX_HEADER_LENGTH);
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
    
    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testGenerate_RFC6455_SingleUnmasked64KBinaryMessage()
    {
        int dataSize = 1024 * 64;
        
        BinaryFrame binary = new BinaryFrame();
        byte payload[] = new byte[dataSize];
        Arrays.fill(payload,(byte)0x44);
        binary.setPayload(ByteBuffer.wrap(payload));
        
        ByteBuffer actual = unitGenerator.generate(binary);
        
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
    
    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testGenerate_RFC6455_SingleUnmaskedPingRequest() throws Exception
    {
        PingFrame ping = new PingFrame().setPayload("Hello");
        
        ByteBuffer actual = unitGenerator.generate(ping);
        
        ByteBuffer expected = ByteBuffer.allocate(10);
        expected.put(new byte[]
                { (byte)0x89, (byte)0x05, (byte)0x48, (byte)0x65, (byte)0x6c, (byte)0x6c, (byte)0x6f });
        expected.flip(); // make readable
        
        ByteBufferAssert.assertEquals("Ping buffers",expected,actual);
    }
    
    /**
     * Example from RFC6455 Spec itself.
     * <p>
     * See <a href="https://tools.ietf.org/html/rfc6455#section-5.7">RFC 6455 Examples section</a>
     * </p>
     */
    @Test
    public void testGenerate_RFC6455_SingleUnmaskedTextMessage()
    {
        WebSocketFrame text = new TextFrame().setPayload("Hello");
        
        ByteBuffer actual = unitGenerator.generate(text);
        
        ByteBuffer expected = ByteBuffer.allocate(10);
        
        expected.put(new byte[]
                { (byte)0x81, (byte)0x05, (byte)0x48, (byte)0x65, (byte)0x6c, (byte)0x6c, (byte)0x6f });
        
        expected.flip();
        
        ByteBufferAssert.assertEquals("t1 buffers are not equal",expected,actual);
    }
    
    /**
     * From Autobahn WebSocket Client Testcase 1.1.2
     */
    @Test
    public void testGenerate_Text_125BytePaylod()
    {
        int length = 125;
        byte buf[] = new byte[length];
        Arrays.fill(buf,(byte)'*');
        String text = new String(buf, StandardCharsets.UTF_8);
        
        WebSocketFrame textFrame = new TextFrame().setPayload(text);
        
        ByteBuffer actual = unitGenerator.generate(textFrame);
        
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
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }
    
    /**
     * From Autobahn WebSocket Client Testcase 1.1.3
     */
    @Test
    public void testGenerate_Text_126BytePaylod()
    {
        int length = 126;
        
        StringBuilder builder = new StringBuilder();
        
        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }
        
        WebSocketFrame textFrame = new TextFrame().setPayload(builder.toString());
        
        ByteBuffer actual = unitGenerator.generate(textFrame);
        
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
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }
    
    /**
     * From Autobahn WebSocket Client Testcase 1.1.4
     */
    @Test
    public void testGenerate_Text_127BytePayload()
    {
        int length = 127;
        
        StringBuilder builder = new StringBuilder();
        
        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }
        
        WebSocketFrame textFrame = new TextFrame().setPayload(builder.toString());
        
        ByteBuffer actual = unitGenerator.generate(textFrame);
        
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
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }
    
    /**
     * From Autobahn WebSocket Client Testcase 1.1.5
     */
    @Test
    public void testGenerate_Text_128BytePayload()
    {
        int length = 128;
        
        StringBuilder builder = new StringBuilder();
        
        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }
        
        WebSocketFrame textFrame = new TextFrame().setPayload(builder.toString());
        
        ByteBuffer actual = unitGenerator.generate(textFrame);
        
        ByteBuffer expected = ByteBuffer.allocate(length + 5);
        
        expected.put(new byte[]
                { (byte)0x81 });
        
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
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }
    
    /**
     * From Autobahn WebSocket Client Testcase 1.1.6
     */
    @Test
    public void testGenerate_Text_65535BytePayload()
    {
        int length = 65535;
        
        StringBuilder builder = new StringBuilder();
        
        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }
        
        WebSocketFrame textFrame = new TextFrame().setPayload(builder.toString());
        
        ByteBuffer actual = unitGenerator.generate(textFrame);
        
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
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }
    
    /**
     * From Autobahn WebSocket Client Testcase 1.1.7
     */
    @Test
    public void testGenerate_Text_65536BytePayload()
    {
        int length = 65536;
        
        StringBuilder builder = new StringBuilder();
        
        for (int i = 0; i < length; ++i)
        {
            builder.append("*");
        }
        
        WebSocketFrame textFrame = new TextFrame().setPayload(builder.toString());
        
        ByteBuffer actual = unitGenerator.generate(textFrame);
        
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
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }
    
    /**
     * From Autobahn WebSocket Client Testcase 1.1.1
     */
    @Test
    public void testGenerate_Text_Empty()
    {
        WebSocketFrame textFrame = new TextFrame().setPayload("");
        
        ByteBuffer actual = unitGenerator.generate(textFrame);
        
        ByteBuffer expected = ByteBuffer.allocate(5);
        
        expected.put(new byte[]
                { (byte)0x81, (byte)0x00 });
        
        expected.flip();
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }
    
    @Test
    public void testGenerate_Text_Hello()
    {
        WebSocketFrame frame = new TextFrame().setPayload("Hello");
        byte utf[] = StringUtil.getUtf8Bytes("Hello");
        assertGeneratedBytes("8105" + Hex.asHex(utf),frame);
    }
    
    @Test
    public void testGenerate_Text_Masked()
    {
        WebSocketFrame frame = new TextFrame().setPayload("Hello");
        byte maskingKey[] = Hex.asByteArray("11223344");
        frame.setMask(maskingKey);

        // what is expected
        StringBuilder expected = new StringBuilder();
        expected.append("8185").append("11223344");
        expected.append(asMaskedHex("Hello",maskingKey));

        // validate
        assertGeneratedBytes(expected,frame);
    }
    
    @Test
    public void testGenerate_Text_Masked_OffsetSourceByteBuffer()
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
        LOG.debug("Payload = {}",BufferUtil.toDetailString(payload));
        WebSocketFrame frame = new TextFrame().setPayload(payload);
        byte maskingKey[] = Hex.asByteArray("11223344");
        frame.setMask(maskingKey);

        // what is expected
        StringBuilder expected = new StringBuilder();
        expected.append("8185").append("11223344");
        expected.append(asMaskedHex("Hello",maskingKey));

        // validate
        assertGeneratedBytes(expected,frame);
    }
    
    /**
     * Test the windowed generate of a frame that has no masking.
     */
    @Test
    public void testGenerate_Windowed()
    {
        // A decent sized frame, no masking
        byte payload[] = new byte[10240];
        Arrays.fill(payload,(byte)0x44);

        WebSocketFrame frame = new BinaryFrame().setPayload(payload);

        // Generate
        int windowSize = 1024;
        WindowHelper helper = new WindowHelper(windowSize);
        ByteBuffer completeBuffer = helper.generateWindowed(frame);

        // Validate
        int expectedHeaderSize = 4;
        int expectedSize = payload.length + expectedHeaderSize;
        int expectedParts = 1;

        helper.assertTotalParts(expectedParts);
        helper.assertTotalBytes(payload.length + expectedHeaderSize);

        assertThat("Generated Buffer",completeBuffer.remaining(),is(expectedSize));
    }
    
    /**
     * This is to prevent a regression in the masking of many frames.
     */
    @Test
    public void testGenerate_WindowedWithMasking() throws Exception
    {
        // A decent sized frame, with masking
        byte payload[] = new byte[10240];
        Arrays.fill(payload,(byte)0x55);

        byte mask[] = new byte[]
        { 0x2A, (byte)0xF0, 0x0F, 0x00 };

        WebSocketFrame frame = new BinaryFrame().setPayload(payload);
        frame.setMask(mask); // masking!

        // Generate
        int windowSize = 2929;
        WindowHelper helper = new WindowHelper(windowSize);
        ByteBuffer completeBuffer = helper.generateWindowed(frame);

        // Validate
        int expectedHeaderSize = 8;
        int expectedSize = payload.length + expectedHeaderSize;
        int expectedParts = 1;

        helper.assertTotalParts(expectedParts);
        helper.assertTotalBytes(payload.length + expectedHeaderSize);

        assertThat("Generated Buffer",completeBuffer.remaining(),is(expectedSize));

        // Parse complete buffer.
        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        ParserCapture capture = new ParserCapture();
        Parser parser = new Parser(policy, new MappedByteBufferPool(), capture);

        parser.parse(completeBuffer);

        // Assert validity of frame
        WebSocketFrame actual = capture.framesQueue.poll(1, TimeUnit.SECONDS);
        assertThat("Frame.opcode",actual.getOpCode(), Matchers.is(OpCode.BINARY));
        assertThat("Frame.payloadLength",actual.getPayloadLength(),is(payload.length));

        // Validate payload contents for proper masking
        ByteBuffer actualData = actual.getPayload().slice();
        assertThat("Frame.payload.remaining",actualData.remaining(),is(payload.length));
        while (actualData.remaining() > 0)
        {
            assertThat("Actual.payload[" + actualData.position() + "]",actualData.get(),is((byte)0x55));
        }
    }
    
    public static class WindowHelper
    {
        final int windowSize;
        int totalParts;
        int totalBytes;

        public WindowHelper(int windowSize)
        {
            this.windowSize = windowSize;
            this.totalParts = 0;
            this.totalBytes = 0;
        }

        public void assertTotalBytes(int expectedBytes)
        {
            assertThat("Generated Bytes",totalBytes,is(expectedBytes));
        }

        public void assertTotalParts(int expectedParts)
        {
            assertThat("Generated Parts",totalParts,is(expectedParts));
        }

        public ByteBuffer generateWindowed(Frame... frames)
        {
            // Create Buffer to hold all generated frames in a single buffer
            int completeBufSize = 0;
            for (Frame f : frames)
            {
                completeBufSize += Generator.MAX_HEADER_LENGTH + f.getPayloadLength();
            }

            ByteBuffer completeBuf = ByteBuffer.allocate(completeBufSize);
            BufferUtil.clearToFill(completeBuf);

            // Generate from all frames
            for (Frame f : frames)
            {
                ByteBuffer header = unitGenerator.generateHeaderBytes(f);
                totalBytes += BufferUtil.put(header,completeBuf);

                if (f.hasPayload())
                {
                    ByteBuffer payload=f.getPayload();
                    totalBytes += payload.remaining();
                    totalParts++;
                    completeBuf.put(payload.slice());
                }
            }

            // Return results
            BufferUtil.flipToFlush(completeBuf,0);
            return completeBuf;
        }
    }
    
    private void assertGeneratedBytes(CharSequence expectedBytes, WebSocketFrame... frames)
    {
        // collect up all frames as single ByteBuffer
        ByteBuffer allframes = unitGenerator.asBuffer(frames);
        // Get hex String form of all frames bytebuffer.
        String actual = Hex.asHex(allframes);
        // Validate
        assertThat("Buffer",actual,is(expectedBytes.toString()));
    }
    
    private String asMaskedHex(String str, byte[] maskingKey)
    {
        byte utf[] = StringUtil.getUtf8Bytes(str);
        mask(utf,maskingKey);
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
}
