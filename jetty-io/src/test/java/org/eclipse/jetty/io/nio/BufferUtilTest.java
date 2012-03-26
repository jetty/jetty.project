package org.eclipse.jetty.io.nio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.junit.Test;

public class BufferUtilTest
{
    @Test
    public void testPut() throws Exception
    {
        ByteBuffer to = BufferUtil.allocate(10);
        ByteBuffer from=BufferUtil.toBuffer("12345");
        
        BufferUtil.clear(to);
        assertEquals(5,BufferUtil.flipPutFlip(from,to));
        assertTrue(BufferUtil.isEmpty(from));
        assertEquals("12345",BufferUtil.toString(to));
        
        from=BufferUtil.toBuffer("XX67890ZZ");
        from.position(2);

        assertEquals(5,BufferUtil.flipPutFlip(from,to));
        assertEquals(2,from.remaining());
        assertEquals("1234567890",BufferUtil.toString(to));
    }   
    

    @Test
    public void testPutDirect() throws Exception
    {
        ByteBuffer to = BufferUtil.allocateDirect(10);
        ByteBuffer from=BufferUtil.toBuffer("12345");
        
        BufferUtil.clear(to);
        assertEquals(5,BufferUtil.flipPutFlip(from,to));
        assertTrue(BufferUtil.isEmpty(from));
        assertEquals("12345",BufferUtil.toString(to));
        
        from=BufferUtil.toBuffer("XX67890ZZ");
        from.position(2);

        assertEquals(5,BufferUtil.flipPutFlip(from,to));
        assertEquals(2,from.remaining());
        assertEquals("1234567890",BufferUtil.toString(to));
    }   

}
