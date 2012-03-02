package org.eclipse.jetty.io.nio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        assertEquals(5,BufferUtil.put(from,to));
        assertTrue(BufferUtil.isEmpty(from));
        assertEquals("12345",BufferUtil.toString(to));
        
        from=BufferUtil.toBuffer("XX67890ZZ");
        from.position(2);

        assertEquals(5,BufferUtil.put(from,to));
        assertEquals(2,from.remaining());
        assertEquals("1234567890",BufferUtil.toString(to));
    }   

    @Test
    public void testPutUnderMax() throws Exception
    {
        ByteBuffer to = BufferUtil.allocate(10);
        ByteBuffer from=BufferUtil.toBuffer("12345");
        
        BufferUtil.clear(to);
        assertEquals(5,BufferUtil.put(from,to,10));
        assertTrue(BufferUtil.isEmpty(from));
        assertEquals("12345",BufferUtil.toString(to));
        
        from=BufferUtil.toBuffer("XX67890ZZ");
        from.position(2);

        assertEquals(5,BufferUtil.put(from,to,10));
        assertEquals(2,from.remaining());
        assertEquals("1234567890",BufferUtil.toString(to));
    }   
    
    @Test
    public void testPutAtMax() throws Exception
    {
        ByteBuffer to = BufferUtil.allocate(10);
        ByteBuffer from=BufferUtil.toBuffer("12345");
        
        BufferUtil.clear(to);
        assertEquals(5,BufferUtil.put(from,to,5));
        assertTrue(BufferUtil.isEmpty(from));
        assertEquals("12345",BufferUtil.toString(to));
        
        from=BufferUtil.toBuffer("XX67890ZZ");
        from.position(2);

        assertEquals(5,BufferUtil.put(from,to,5));
        assertEquals(2,from.remaining());
        assertEquals("1234567890",BufferUtil.toString(to));
    }   
    

    @Test
    public void testPutOverMax() throws Exception
    {
        ByteBuffer to = BufferUtil.allocate(10);
        ByteBuffer from=BufferUtil.toBuffer("12345");
        
        BufferUtil.clear(to);
        assertEquals(4,BufferUtil.put(from,to,4));
        assertEquals(1,from.remaining());
        assertEquals("1234",BufferUtil.toString(to));
        
        from=BufferUtil.toBuffer("XX567890ZZ");
        from.position(2);

        assertEquals(4,BufferUtil.put(from,to,4));
        assertEquals(4,from.remaining());
        assertEquals("12345678",BufferUtil.toString(to));
    }   
    

    @Test
    public void testPutDirect() throws Exception
    {
        ByteBuffer to = BufferUtil.allocateDirect(10);
        ByteBuffer from=BufferUtil.toBuffer("12345");
        
        BufferUtil.clear(to);
        assertEquals(5,BufferUtil.put(from,to));
        assertTrue(BufferUtil.isEmpty(from));
        assertEquals("12345",BufferUtil.toString(to));
        
        from=BufferUtil.toBuffer("XX67890ZZ");
        from.position(2);

        assertEquals(5,BufferUtil.put(from,to));
        assertEquals(2,from.remaining());
        assertEquals("1234567890",BufferUtil.toString(to));
    }   

    @Test
    public void testPutUnderMaxDirect() throws Exception
    {
        ByteBuffer to = BufferUtil.allocateDirect(10);
        ByteBuffer from=BufferUtil.toBuffer("12345");
        
        BufferUtil.clear(to);
        assertEquals(5,BufferUtil.put(from,to,10));
        assertTrue(BufferUtil.isEmpty(from));
        assertEquals("12345",BufferUtil.toString(to));
        
        from=BufferUtil.toBuffer("XX67890ZZ");
        from.position(2);

        assertEquals(5,BufferUtil.put(from,to,10));
        assertEquals(2,from.remaining());
        assertEquals("1234567890",BufferUtil.toString(to));
    }   
    
    @Test
    public void testPutAtMaxDirect() throws Exception
    {
        ByteBuffer to = BufferUtil.allocateDirect(10);
        ByteBuffer from=BufferUtil.toBuffer("12345");
        
        BufferUtil.clear(to);
        assertEquals(5,BufferUtil.put(from,to,5));
        assertTrue(BufferUtil.isEmpty(from));
        assertEquals("12345",BufferUtil.toString(to));
        
        from=BufferUtil.toBuffer("XX67890ZZ");
        from.position(2);

        assertEquals(5,BufferUtil.put(from,to,5));
        assertEquals(2,from.remaining());
        assertEquals("1234567890",BufferUtil.toString(to));
    }   
    

    @Test
    public void testPutOverMaxDirect() throws Exception
    {
        ByteBuffer to = BufferUtil.allocateDirect(10);
        ByteBuffer from=BufferUtil.toBuffer("12345");
        
        BufferUtil.clear(to);
        assertEquals(4,BufferUtil.put(from,to,4));
        assertEquals(1,from.remaining());
        assertEquals("1234",BufferUtil.toString(to));
        
        from=BufferUtil.toBuffer("XX567890ZZ");
        from.position(2);

        assertEquals(4,BufferUtil.put(from,to,4));
        assertEquals(4,from.remaining());
        assertEquals("12345678",BufferUtil.toString(to));
    }   
}
