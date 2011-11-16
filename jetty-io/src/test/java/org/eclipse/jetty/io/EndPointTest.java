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
        
        // Client sends a request
        c.client.flush(new ByteArrayBuffer("request"));
        
        // Server receives the request
        int len = c.server.fill(buffer);
        assertEquals(7,len);
        assertEquals("request",buffer.toString());

        // Client and server are open
        assertTrue(c.client.isOpen());
        assertFalse(c.client.isInputShutdown());
        assertFalse(c.client.isOutputShutdown());
        assertTrue(c.server.isOpen());
        assertFalse(c.server.isInputShutdown());
        assertFalse(c.server.isOutputShutdown());
        
        // Server sends response and closes output
        c.server.flush(new ByteArrayBuffer("response"));
        c.server.shutdownOutput();
        
        // client server are open, server is oshut
        assertTrue(c.client.isOpen());
        assertFalse(c.client.isInputShutdown());
        assertFalse(c.client.isOutputShutdown());
        assertTrue(c.server.isOpen());
        assertFalse(c.server.isInputShutdown());
        assertTrue(c.server.isOutputShutdown());
        
        // Client reads response
        buffer.clear();
        len = c.client.fill(buffer);
        assertEquals(8,len);
        assertEquals("response",buffer.toString());

        // Client and server are open, server is oshut
        assertTrue(c.client.isOpen());
        assertFalse(c.client.isInputShutdown());
        assertFalse(c.client.isOutputShutdown());
        assertTrue(c.server.isOpen());
        assertFalse(c.server.isInputShutdown());
        assertTrue(c.server.isOutputShutdown());
        
        // Client reads -1
        buffer.clear();
        len = c.client.fill(buffer);
        assertEquals(-1,len);

        // Client and server are open, server is oshut, client is ishut
        assertTrue(c.client.isOpen());
        assertTrue(c.client.isInputShutdown());
        assertFalse(c.client.isOutputShutdown());
        assertTrue(c.server.isOpen());
        assertFalse(c.server.isInputShutdown());
        assertTrue(c.server.isOutputShutdown());
        
        // Client shutsdown output, which is a close because already ishut
        c.client.shutdownOutput();

        // Client is closed. Server is open and oshut
        assertFalse(c.client.isOpen());
        assertTrue(c.client.isInputShutdown());
        assertTrue(c.client.isOutputShutdown());
        assertTrue(c.server.isOpen());
        assertFalse(c.server.isInputShutdown());
        assertTrue(c.server.isOutputShutdown());

        // Server reads close
        buffer.clear();
        len = c.server.fill(buffer);
        assertEquals(-1,len);

        // Client and Server are closed
        assertFalse(c.client.isOpen());
        assertTrue(c.client.isInputShutdown());
        assertTrue(c.client.isOutputShutdown());
        assertFalse(c.server.isOpen());
        assertTrue(c.server.isInputShutdown());
        assertTrue(c.server.isOutputShutdown());
        
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
