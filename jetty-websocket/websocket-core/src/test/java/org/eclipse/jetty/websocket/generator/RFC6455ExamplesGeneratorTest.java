package org.eclipse.jetty.websocket.generator;


import java.nio.ByteBuffer;

import junit.framework.Assert;

import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.websocket.ByteBufferAssert;
import org.eclipse.jetty.websocket.Debug;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.PingFrame;
import org.eclipse.jetty.websocket.frames.PongFrame;
import org.eclipse.jetty.websocket.frames.TextFrame;
import org.eclipse.jetty.websocket.masks.FixedMasker;
import org.junit.Ignore;
import org.junit.Test;

public class RFC6455ExamplesGeneratorTest
{
    StandardByteBufferPool bufferPool = new StandardByteBufferPool();

    @Test
    @Ignore ("need to fix still")
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
        t1.setData("Hel");
        t2.setData("lo");
        
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
        
        Assert.assertEquals(b1.get(),g1.get());
        
        ByteBufferAssert.assertEquals("t1 buffers are not equal", b1, g1);
        ByteBufferAssert.assertEquals("t2 buffers are not equal", b2, g2);

    }
    
    
    
    @Test
    @Ignore ("need to fix still")
    public void testSingleMaskedPongRequest()
    {
        ByteBuffer b1 = ByteBuffer.allocate(11);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // Unmasked Ping request
        b1.put(new byte[]
                { (byte)0x8a, (byte)0x85, (byte)0x37, (byte)0xfa, (byte)0x21, (byte)0x3d, (byte)0x7f, (byte)0x9f, (byte)0x4d, (byte)0x51, (byte)0x58 });

        PongFrame pong = new PongFrame();
        ByteBuffer payload = ByteBuffer.allocate(5);
        payload.put("Hello".getBytes(), 0, 5);
        pong.setPayload(payload);
        pong.setFin(true);
        pong.setMasked(true);
        pong.setMask(new byte[]{(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff});
        
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        policy.setMasker(new FixedMasker());

        PongFrameGenerator t1 = new PongFrameGenerator(bufferPool,policy);

        ByteBuffer g1 = t1.generate(pong);
        
        b1.flip();
        g1.flip();
        
        Debug.dumpState(b1);
        Debug.dumpState(g1);

        ByteBufferAssert.assertEquals("pong buffers are not equal", b1, g1);

    }


    @Test
    public void testSingleUnmaskedPingRequest() throws Exception
    {

        ByteBuffer b1 = ByteBuffer.allocate(7);
        b1.put(new byte[] {
                (byte)0x89, (byte)0x05, (byte)0x48, (byte)0x65, (byte)0x6c, (byte)0x6c, (byte)0x6f
        });

        PingFrame ping = new PingFrame();
        ping.setFin(true);
        ByteBuffer payload = ByteBuffer.allocate(5);

        payload.put("Hello".getBytes(), 0, 5);
        ping.setPayload(payload);

        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        PingFrameGenerator t1 = new PingFrameGenerator(bufferPool, policy);

        ByteBuffer g1 = t1.generate(ping);

        b1.flip();
        g1.flip();
        
        Debug.dumpState(b1);
        Debug.dumpState(g1);

        ByteBufferAssert.assertEquals("ping buffers are not equal",b1,g1);

    }

    
    @Test
    @Ignore ("need to fix still")
    public void testSingleMaskedTextMessage()
    {
        ByteBuffer b1 = ByteBuffer.allocate(11);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // A single-frame masked text message
        b1.put(new byte[]
                { (byte)0x81, (byte)0x85, 0x37, (byte)0xfa, 0x21, 0x3d, 0x7f, (byte)0x9f, 0x4d, 0x51, 0x58 });

        TextFrame t1 = new TextFrame();
        t1.setData("Hello");
        t1.setFin(true);
        t1.setMasked(true);
        t1.setMask(new byte[]{(byte)0xff,(byte)0xff,(byte)0xff,(byte)0xff});
        
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);
        
        TextFrameGenerator generator = new TextFrameGenerator(bufferPool,policy);
        
        ByteBuffer g1 = generator.generate(t1);
        
        Debug.dumpState(b1);
        Debug.dumpState(g1);

        b1.flip();
        g1.flip();
        
        ByteBufferAssert.assertEquals("masked text buffers are not equal",b1,g1);

    }
}


