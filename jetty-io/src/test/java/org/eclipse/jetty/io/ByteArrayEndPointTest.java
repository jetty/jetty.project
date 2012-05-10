package org.eclipse.jetty.io;

import static junit.framework.Assert.assertEquals;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.junit.Test;

public class ByteArrayEndPointTest
{
    @Test
    public void testFill() throws Exception
    {
        ByteArrayEndPoint endp = new ByteArrayEndPoint();
        endp.setInput("test input");
        
        ByteBuffer buffer = BufferUtil.allocate(1024);
        
        assertEquals(10,endp.fill(buffer));
        assertEquals("test input",BufferUtil.toString(buffer));
        
        assertEquals(0,endp.fill(buffer));
        
        endp.setInput(" more");
        assertEquals(5,endp.fill(buffer));
        assertEquals("test input more",BufferUtil.toString(buffer));
        
        assertEquals(0,endp.fill(buffer));

        endp.setInput((ByteBuffer)null);

        assertEquals(-1,endp.fill(buffer));
        
        endp.close();
        
        try
        {
            endp.fill(buffer);
            fail();
        }
        catch(IOException e)
        {
            assertThat(e.getMessage(),containsString("CLOSED"));
        }
        
        endp.reset();
        endp.setInput("and more");
        buffer = BufferUtil.allocate(4);
        
        assertEquals(4,endp.fill(buffer));
        assertEquals("and ",BufferUtil.toString(buffer));
        assertEquals(0,endp.fill(buffer));
        BufferUtil.clear(buffer);
        assertEquals(4,endp.fill(buffer));
        assertEquals("more",BufferUtil.toString(buffer));
        
    }
    
    @Test
    public void testGrowingFlush() throws Exception
    {
        ByteArrayEndPoint endp = new ByteArrayEndPoint((byte[])null,15);
        endp.setGrowOutput(true);
        
        assertEquals(11,endp.flush(BufferUtil.toBuffer("some output")));
        assertEquals("some output",endp.getOutputString());
        
        assertEquals(10,endp.flush(BufferUtil.toBuffer(" some more")));
        assertEquals("some output some more",endp.getOutputString());
        
        assertEquals(0,endp.flush());
        assertEquals("some output some more",endp.getOutputString());
        
        assertEquals(0,endp.flush(BufferUtil.EMPTY_BUFFER));
        assertEquals("some output some more",endp.getOutputString());
        
        assertEquals(9,endp.flush(BufferUtil.EMPTY_BUFFER,BufferUtil.toBuffer(" and"),BufferUtil.toBuffer(" more")));
        assertEquals("some output some more and more",endp.getOutputString());
        
        
        
    }
    
    @Test
    public void testFlush() throws Exception
    {
        ByteArrayEndPoint endp = new ByteArrayEndPoint((byte[])null,15);
        endp.setGrowOutput(false);
        endp.setOutput(BufferUtil.allocate(10));

        ByteBuffer data = BufferUtil.toBuffer("Some more data.");
        assertEquals(10,endp.flush(data));
        assertEquals("Some more ",endp.getOutputString());
        assertEquals("data.",BufferUtil.toString(data));

        assertEquals("Some more ",endp.takeOutputString());
        
        assertEquals(5,endp.flush(data));
        assertEquals("data.",BufferUtil.toString(endp.takeOutput()));
    }
    
    
    
}
