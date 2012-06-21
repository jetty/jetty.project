package org.eclipse.jetty.websocket.generator;


import java.nio.ByteBuffer;

import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.ByteBufferAssert;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.PingFrame;
import org.eclipse.jetty.websocket.frames.PongFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;
import org.junit.Test;

public class RFC6455ExamplesGeneratorTest
{
    StandardByteBufferPool bufferPool = new StandardByteBufferPool();

    @Test
    public void testFragmentedUnmaskedTextMessage()
    {
        ByteBuffer b1 = ByteBuffer.allocate(5);

        b1.put(new byte[]
                { (byte)0x01, (byte)0x03, (byte)0x48, (byte)0x65, (byte)0x6c });

        ByteBuffer b2 = ByteBuffer.allocate(4);

        b2.put(new byte[]
                { (byte)0x80, (byte)0x02, (byte)0x6c, (byte)0x6f });

        TextFrame t1 = new TextFrame();
        TextFrame t2 = new TextFrame();

        t1.setFin(false);
        t2.setFin(true);
        t2.setContinuation(true);
        t1.setPayload("Hel");
        t2.setPayload("lo");

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);

        TextFrameGenerator generator = new TextFrameGenerator(bufferPool,policy);

        ByteBuffer g1 = generator.generate(t1);
        ByteBuffer g2 = generator.generate(t2);

        //Debug.dumpState(b1);
        //Debug.dumpState(g1);

        b1.flip();
        g1.flip();
        b2.flip();
        g2.flip();

        ByteBufferAssert.assertEquals("t1 buffers are not equal", b1, g1);
        ByteBufferAssert.assertEquals("t2 buffers are not equal", b2, g2);
    }

    @Test
    public void testSingleMaskedPongRequest()
    {
        PongFrame pong = new PongFrame();
        pong.setMask(new byte[]
                { 0x37, (byte)0xfa, 0x21, 0x3d });

        byte msg[] = "Hello".getBytes(StringUtil.__UTF8_CHARSET);
        ByteBuffer payload = ByteBuffer.allocate(msg.length);
        payload.put(msg);
        pong.setPayload(payload);

        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();
        PongFrameGenerator gen = new PongFrameGenerator(bufferPool,policy);

        ByteBuffer actual = gen.generate(pong);
        actual.flip(); // make readable

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
        TextFrame text = new TextFrame();
        text.setPayload("Hello");
        text.setFin(true);
        text.setMask(new byte[]
        { 0x37, (byte)0xfa, 0x21, 0x3d });

        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();

        TextFrameGenerator gen = new TextFrameGenerator(bufferPool,policy);

        ByteBuffer actual = gen.generate(text);
        actual.flip(); // make readable

        ByteBuffer expected = ByteBuffer.allocate(11);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // A single-frame masked text message
        expected.put(new byte[]
        { (byte)0x81, (byte)0x85, 0x37, (byte)0xfa, 0x21, 0x3d, 0x7f, (byte)0x9f, 0x4d, 0x51, 0x58 });
        expected.flip(); // make readable

        ByteBufferAssert.assertEquals("masked text buffers are not equal",expected,actual);
    }


    @Test
    public void testSingleUnmaskedPingRequest() throws Exception
    {
        PingFrame ping = new PingFrame();

        byte msg[] = "Hello".getBytes(StringUtil.__UTF8_CHARSET);
        ByteBuffer payload = ByteBuffer.allocate(msg.length);
        payload.put(msg);
        ping.setPayload(payload);

        WebSocketPolicy policy = WebSocketPolicy.newServerPolicy();

        PingFrameGenerator gen = new PingFrameGenerator(bufferPool,policy);
        ByteBuffer actual = gen.generate(ping);
        actual.flip(); // make readable

        ByteBuffer expected = ByteBuffer.allocate(10);
        expected.put(new byte[]
                { (byte)0x89, (byte)0x05, (byte)0x48, (byte)0x65, (byte)0x6c, (byte)0x6c, (byte)0x6f });
        expected.flip(); // make readable

        ByteBufferAssert.assertEquals("Ping buffers",expected,actual);
    }
}


