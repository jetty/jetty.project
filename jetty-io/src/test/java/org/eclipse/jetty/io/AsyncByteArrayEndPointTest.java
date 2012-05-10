package org.eclipse.jetty.io;

import static junit.framework.Assert.assertEquals;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.junit.Assert;
import org.junit.Test;

public class AsyncByteArrayEndPointTest
{
    @Test
    public void testReadable() throws Exception
    {
        AsyncByteArrayEndPoint endp = new AsyncByteArrayEndPoint();
        endp.setInput("test input");
        
        ByteBuffer buffer = BufferUtil.allocate(1024);
        FutureCallback<String> fcb = new FutureCallback<>();
        
        endp.readable("CTX",fcb);
        assertTrue(fcb.isDone());
        assertEquals("CTX",fcb.get());
        assertEquals(10,endp.fill(buffer));
        assertEquals("test input",BufferUtil.toString(buffer));
        
        fcb = new FutureCallback<>();
        endp.readable("CTX",fcb);
        assertFalse(fcb.isDone());
        assertEquals(0,endp.fill(buffer));
        
        endp.setInput(" more");
        assertTrue(fcb.isDone());
        assertEquals("CTX",fcb.get());
        assertEquals(5,endp.fill(buffer));
        assertEquals("test input more",BufferUtil.toString(buffer));

        fcb = new FutureCallback<>();
        endp.readable("CTX",fcb);
        assertFalse(fcb.isDone());
        assertEquals(0,endp.fill(buffer));

        endp.setInput((ByteBuffer)null);
        assertTrue(fcb.isDone());
        assertEquals("CTX",fcb.get());
        assertEquals(-1,endp.fill(buffer));
        
        fcb = new FutureCallback<>();
        endp.readable("CTX",fcb);
        assertTrue(fcb.isDone());
        assertEquals("CTX",fcb.get());
        assertEquals(-1,endp.fill(buffer));
        
        endp.close();

        fcb = new FutureCallback<>();
        endp.readable("CTX",fcb);
        assertTrue(fcb.isDone());
        try
        {
            fcb.get();
            fail();
        }
        catch(ExecutionException e)
        {
            assertThat(e.toString(),containsString("Closed"));
        }
        
    }

    @Test
    public void testWrite() throws Exception
    {
        AsyncByteArrayEndPoint endp = new AsyncByteArrayEndPoint((byte[])null,15);
        endp.setGrowOutput(false);
        endp.setOutput(BufferUtil.allocate(10));

        ByteBuffer data = BufferUtil.toBuffer("Data.");
        ByteBuffer more = BufferUtil.toBuffer(" Some more.");

        FutureCallback<String> fcb = new FutureCallback<>();
        endp.write("CTX",fcb,data);
        assertTrue(fcb.isDone());
        assertEquals("CTX",fcb.get());
        assertEquals("Data.",endp.getOutputString());
        
        fcb = new FutureCallback<>();
        endp.write("CTX",fcb,more);
        assertFalse(fcb.isDone());

        assertEquals("Data. Some",endp.getOutputString());
        assertEquals("Data. Some",endp.takeOutputString());

        assertTrue(fcb.isDone());
        assertEquals("CTX",fcb.get());
        assertEquals(" more.",endp.getOutputString());
        
    }
}
