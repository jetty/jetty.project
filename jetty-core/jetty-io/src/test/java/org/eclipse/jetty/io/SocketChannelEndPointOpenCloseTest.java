//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SocketChannelEndPointOpenCloseTest
{
    public static class EndPointPair
    {
        public SocketChannelEndPoint client;
        public SocketChannelEndPoint server;
    }

    static ServerSocketChannel connector;

    @BeforeAll
    public static void open() throws Exception
    {
        connector = ServerSocketChannel.open();
        connector.socket().bind(null);
    }

    @AfterAll
    public static void close() throws Exception
    {
        connector.close();
        connector = null;
    }

    private EndPointPair newConnection() throws Exception
    {
        EndPointPair c = new EndPointPair();

        c.client = new SocketChannelEndPoint(SocketChannel.open(connector.socket().getLocalSocketAddress()), null, null, null);
        c.server = new SocketChannelEndPoint(connector.accept(), null, null, null);
        return c;
    }

    @Test
    public void testClientServerExchange() throws Exception
    {
        EndPointPair c = newConnection();
        ByteBuffer buffer = BufferUtil.allocate(4096);

        // Client sends a request
        c.client.flush(BufferUtil.toBuffer("request"));

        // Server receives the request
        int len = c.server.fill(buffer);
        assertEquals(7, len);
        assertEquals("request", BufferUtil.toString(buffer));

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
        assertEquals(8, len);
        assertEquals("response", BufferUtil.toString(buffer));

        // Client and server are open, server is oshut
        assertTrue(c.client.isOpen());
        assertFalse(c.client.isOutputShutdown());
        assertTrue(c.server.isOpen());
        assertTrue(c.server.isOutputShutdown());

        // Client reads -1
        BufferUtil.clear(buffer);
        len = c.client.fill(buffer);
        assertEquals(-1, len);

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
        assertEquals(-1, len);

        // Client and Server are closed
        assertFalse(c.client.isOpen());
        assertTrue(c.client.isOutputShutdown());
        assertFalse(c.server.isOpen());
        assertTrue(c.server.isOutputShutdown());
    }

    @Test
    public void testClientClose() throws Exception
    {
        EndPointPair c = newConnection();
        ByteBuffer buffer = BufferUtil.allocate(4096);

        c.client.flush(BufferUtil.toBuffer("request"));
        int len = c.server.fill(buffer);
        assertEquals(7, len);
        assertEquals("request", BufferUtil.toString(buffer));

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
        assertEquals(-1, len);

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
