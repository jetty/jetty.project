//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
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
        ByteBuffer buffer = BufferUtil.allocate(4096);

        // Client sends a request
        c.client.flush(BufferUtil.toBuffer("request"));

        // Server receives the request
        int len = c.server.fill(buffer);
        assertEquals(7,len);
        assertEquals("request",BufferUtil.toString(buffer));

        // Client and server are open
        assertTrue(c.client.isOpen());
        assertFalse(c.client.isOutputShutdown());
        assertTrue(c.server.isOpen());
        assertFalse(c.server.isOutputShutdown());

        // Server sends response and closes output
        c.server.flush(BufferUtil.toBuffer("response"));
        c.server.shutdownOutput();

        // client server are open, server is oshut
        assertTrue(c.client.isOpen());
        assertFalse(c.client.isOutputShutdown());
        assertTrue(c.server.isOpen());
        assertTrue(c.server.isOutputShutdown());

        // Client reads response
        BufferUtil.clear(buffer);
        len = c.client.fill(buffer);
        assertEquals(8,len);
        assertEquals("response",BufferUtil.toString(buffer));

        // Client and server are open, server is oshut
        assertTrue(c.client.isOpen());
        assertFalse(c.client.isOutputShutdown());
        assertTrue(c.server.isOpen());
        assertTrue(c.server.isOutputShutdown());

        // Client reads -1
        BufferUtil.clear(buffer);
        len = c.client.fill(buffer);
        assertEquals(-1,len);

        // Client and server are open, server is oshut, client is ishut
        assertTrue(c.client.isOpen());
        assertFalse(c.client.isOutputShutdown());
        assertTrue(c.server.isOpen());
        assertTrue(c.server.isOutputShutdown());

        // Client shutsdown output, which is a close because already ishut
        c.client.shutdownOutput();

        // Client is closed. Server is open and oshut
        assertFalse(c.client.isOpen());
        assertTrue(c.client.isOutputShutdown());
        assertTrue(c.server.isOpen());
        assertTrue(c.server.isOutputShutdown());

        // Server reads close
        BufferUtil.clear(buffer);
        len = c.server.fill(buffer);
        assertEquals(-1,len);

        // Client and Server are closed
        assertFalse(c.client.isOpen());
        assertTrue(c.client.isOutputShutdown());
        assertFalse(c.server.isOpen());
        assertTrue(c.server.isOutputShutdown());

    }



    @Test
    public void testClientClose() throws Exception
    {
        EndPointPair<T> c = newConnection();
        ByteBuffer buffer = BufferUtil.allocate(4096);

        c.client.flush(BufferUtil.toBuffer("request"));
        int len = c.server.fill(buffer);
        assertEquals(7,len);
        assertEquals("request",BufferUtil.toString(buffer));

        assertTrue(c.client.isOpen());
        assertFalse(c.client.isOutputShutdown());
        assertTrue(c.server.isOpen());
        assertFalse(c.server.isOutputShutdown());

        c.client.close();

        assertFalse(c.client.isOpen());
        assertTrue(c.client.isOutputShutdown());
        assertTrue(c.server.isOpen());
        assertFalse(c.server.isOutputShutdown());

        len = c.server.fill(buffer);
        assertEquals(-1,len);

        assertFalse(c.client.isOpen());
        assertTrue(c.client.isOutputShutdown());
        assertTrue(c.server.isOpen());
        assertFalse(c.server.isOutputShutdown());

        c.server.shutdownOutput();

        assertFalse(c.client.isOpen());
        assertTrue(c.client.isOutputShutdown());
        assertFalse(c.server.isOpen());
        assertTrue(c.server.isOutputShutdown());
    }

}
