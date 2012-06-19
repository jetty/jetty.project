package org.eclipse.jetty.websocket.generator;


import java.nio.ByteBuffer;

import org.eclipse.jetty.io.StandardByteBufferPool;
import org.eclipse.jetty.websocket.ByteBufferAssert;
import org.eclipse.jetty.websocket.Debug;
import org.eclipse.jetty.websocket.api.WebSocketSettings;
import org.eclipse.jetty.websocket.frames.PingFrame;
import org.eclipse.jetty.websocket.frames.PongFrame;
import org.junit.Assert;
import org.junit.Test;

public class RFC6455ExamplesGeneratorTest
{
    StandardByteBufferPool bufferPool = new StandardByteBufferPool();
    
    @Test
    public void testSingleUnmaskedPingRequest() throws Exception
    {
        
        ByteBuffer buf = ByteBuffer.allocate(7);
        buf.put(new byte[] {
                (byte)0x89, (byte)0x05, (byte)0x48, (byte)0x65, (byte)0x6c, (byte)0x6c, (byte)0x6f
        });
        
        //buffer.flip();
        
        PingFrame ping = new PingFrame();
        ByteBuffer payload = ByteBuffer.allocate(5);

        payload.put("Hello".getBytes(), 0, 5);
        ping.setPayload(payload);
        
        PingFrameGenerator generator = new PingFrameGenerator(bufferPool, new WebSocketSettings());
        
        ByteBuffer generatedPing = generator.generate(ping);

        Debug.dumpState(buf);
        Debug.dumpState(generatedPing);
        
        ByteBufferAssert.assertEquals("ping buffers are not equal",buf,generatedPing);
    
    }
    
    
    @Test
    public void testSingleMaskedPongRequest()
    {
        ByteBuffer buf = ByteBuffer.allocate(11);
        // Raw bytes as found in RFC 6455, Section 5.7 - Examples
        // Unmasked Ping request
        buf.put(new byte[]
                { (byte)0x8a, (byte)0x85, (byte)0x37, (byte)0xfa, (byte)0x21, (byte)0x3d, (byte)0x7f, (byte)0x9f, (byte)0x4d, (byte)0x51, (byte)0x58 });
        //buf.flip();

        PongFrame pong = new PongFrame();
        ByteBuffer payload = ByteBuffer.allocate(5);
        payload.put("Hello".getBytes(), 0, 5);
        pong.setPayload(payload);

        PongFrameGenerator generator = new PongFrameGenerator(bufferPool, new WebSocketSettings());

        ByteBuffer generatedPong = generator.generate(pong);
        Debug.dumpState(buf);
        Debug.dumpState(generatedPong);
        
        ByteBufferAssert.assertEquals("pong buffers are not equal", buf, generatedPong);

    }
    
   
}

