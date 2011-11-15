package org.eclipse.jetty.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.junit.Test;

public abstract class EndPointTest<T extends EndPoint>
{
    public static class EndPointPair<T>
    {
        public T client;
        public T server;
    }
    
    protected abstract EndPointPair<T> newConnection() throws Exception;
   

    @Test
    public void testClientServerExchange() throws Exception
    {
        EndPointPair<T> c = newConnection();
        Buffer buffer = new IndirectNIOBuffer(4096);
        
        c.client.flush(new ByteArrayBuffer("request"));
        int len = c.server.fill(buffer);
        assertEquals(7,len);
        assertEquals("request",buffer.toString());

        assertTrue(c.client.isOpen());
        assertFalse(c.client.isInputShutdown());
        assertFalse(c.client.isOutputShutdown());
        assertTrue(c.server.isOpen());
        assertFalse(c.server.isInputShutdown());
        assertFalse(c.server.isOutputShutdown());
        
        c.server.flush(new ByteArrayBuffer("response"));
        c.server.shutdownOutput();
        
        assertTrue(c.client.isOpen());
        assertFalse(c.client.isInputShutdown());
        assertFalse(c.client.isOutputShutdown());
        assertTrue(c.server.isOpen());
        assertFalse(c.server.isInputShutdown());
        assertTrue(c.server.isOutputShutdown());
        
        buffer.clear();
        len = c.client.fill(buffer);
        assertEquals(8,len);
        assertEquals("response",buffer.toString());

        assertTrue(c.client.isOpen());
        assertFalse(c.client.isInputShutdown());
        assertFalse(c.client.isOutputShutdown());
        assertTrue(c.server.isOpen());
        assertFalse(c.server.isInputShutdown());
        assertTrue(c.server.isOutputShutdown());
        
        buffer.clear();
        len = c.client.fill(buffer);
        assertEquals(-1,len);
        
        if (!c.client.isOpen())
        {
            // Half closing is not working - maybe an OS thing
            assertTrue(c.client.isInputShutdown());
            assertTrue(c.client.isOutputShutdown());
            assertFalse(c.server.isOpen());
            assertTrue(c.server.isInputShutdown());
            assertTrue(c.server.isOutputShutdown());
        }
        else
        {
            // Half closing is working

            assertTrue(c.client.isInputShutdown());
            assertFalse(c.client.isOutputShutdown());
            assertTrue(c.server.isOpen());
            assertFalse(c.server.isInputShutdown());
            assertTrue(c.server.isOutputShutdown());

            c.client.shutdownOutput();

            assertFalse(c.client.isOpen());
            assertTrue(c.client.isInputShutdown());
            assertTrue(c.client.isOutputShutdown());
            assertTrue(c.server.isOpen());
            assertFalse(c.server.isInputShutdown());
            assertTrue(c.server.isOutputShutdown());

            buffer.clear();
            len = c.server.fill(buffer);
            assertEquals(-1,len);

            assertFalse(c.client.isOpen());
            assertTrue(c.client.isInputShutdown());
            assertTrue(c.client.isOutputShutdown());
            assertFalse(c.server.isOpen());
            assertTrue(c.server.isInputShutdown());
            assertTrue(c.server.isOutputShutdown());
        }
        
    }
    


    @Test
    public void testClientClose() throws Exception
    {
        EndPointPair<T> c = newConnection();
        Buffer buffer = new IndirectNIOBuffer(4096);
        
        c.client.flush(new ByteArrayBuffer("request"));
        int len = c.server.fill(buffer);
        assertEquals(7,len);
        assertEquals("request",buffer.toString());

        assertTrue(c.client.isOpen());
        assertFalse(c.client.isInputShutdown());
        assertFalse(c.client.isOutputShutdown());
        assertTrue(c.server.isOpen());
        assertFalse(c.server.isInputShutdown());
        assertFalse(c.server.isOutputShutdown());        
        
        c.client.close();

        assertFalse(c.client.isOpen());
        assertTrue(c.client.isInputShutdown());
        assertTrue(c.client.isOutputShutdown());
        assertTrue(c.server.isOpen());
        assertFalse(c.server.isInputShutdown());
        assertFalse(c.server.isOutputShutdown());  
        
        len = c.server.fill(buffer);
        assertEquals(-1,len);

        if (!c.server.isOpen())
        {
            // Half closing is not working - maybe an OS thing
            assertFalse(c.client.isOpen());
            assertTrue(c.client.isInputShutdown());
            assertTrue(c.client.isOutputShutdown());
            assertFalse(c.server.isOpen());
            assertTrue(c.server.isInputShutdown());
            assertTrue(c.server.isOutputShutdown()); 
        }
        else
        {
            // Half closing is working
            assertFalse(c.client.isOpen());
            assertTrue(c.client.isInputShutdown());
            assertTrue(c.client.isOutputShutdown());
            assertTrue(c.server.isOpen());
            assertTrue(c.server.isInputShutdown());
            assertFalse(c.server.isOutputShutdown());  

            c.server.shutdownOutput();

            assertFalse(c.client.isOpen());
            assertTrue(c.client.isInputShutdown());
            assertTrue(c.client.isOutputShutdown());
            assertFalse(c.server.isOpen());
            assertTrue(c.server.isInputShutdown());
            assertTrue(c.server.isOutputShutdown());  
        }
    }   
    
}
