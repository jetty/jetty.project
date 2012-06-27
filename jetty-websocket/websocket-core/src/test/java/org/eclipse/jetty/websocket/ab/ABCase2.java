package org.eclipse.jetty.websocket.ab;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.ByteBufferAssert;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.PingFrame;
import org.eclipse.jetty.websocket.generator.Generator;
import org.junit.Test;

public class ABCase2
{
    @Test
    public void testGenerateEmptyPingCase2_1()
    {
        PingFrame pingFrame = new PingFrame();
        
        WebSocketPolicy policy = new WebSocketPolicy(WebSocketBehavior.SERVER);

        Generator generator = new Generator(policy);
        ByteBuffer actual = ByteBuffer.allocate(32);
        generator.generate(actual, pingFrame);

        ByteBuffer expected = ByteBuffer.allocate(5);

        expected.put(new byte[]
                { (byte)0x89, (byte)0x00 });
        
        actual.flip();
        expected.flip();
        
        ByteBufferAssert.assertEquals("buffers do not match",expected,actual);
    }
    
}
