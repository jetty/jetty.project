// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.io;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Reader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

import org.eclipse.jetty.util.IO;
import org.junit.Test;

/**
 *
 */
public class IOTest
{
    @Test
    public void testIO() throws InterruptedException
    {
        // Only a little test
        ByteArrayInputStream in = new ByteArrayInputStream
            ("The quick brown fox jumped over the lazy dog".getBytes());
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        IO.copyThread(in,out);
        Thread.sleep(1500);
        System.err.println(out);

        assertEquals( "copyThread",
                      out.toString(),
                      "The quick brown fox jumped over the lazy dog");
    }
    
    @Test
    public void testHalfCloses() throws Exception
    {
        ServerSocket connector = new ServerSocket(0);
        
        Socket client = new Socket("localhost",connector.getLocalPort());
        System.err.println(client);
        Socket server = connector.accept();
        System.err.println(server);
        
        // we can write both ways
        client.getOutputStream().write(1);
        assertEquals(1,server.getInputStream().read());
        server.getOutputStream().write(1);
        assertEquals(1,client.getInputStream().read());
        
        // shutdown output results in read -1
        client.shutdownOutput();
        assertEquals(-1,server.getInputStream().read());
        
        // Even though EOF has been read, the server input is not seen as shutdown 
        assertFalse(server.isInputShutdown());
        
        // and we can read -1 again
        assertEquals(-1,server.getInputStream().read());

        // but cannot write
        try { client.getOutputStream().write(1); assertTrue(false); } catch (SocketException e) {}
   
        // but can still write in opposite direction.
        server.getOutputStream().write(1);
        assertEquals(1,client.getInputStream().read());
   
        
        // server can shutdown input to match the shutdown out of client
        server.shutdownInput();
        
        // now we EOF instead of reading -1
        try { server.getInputStream().read(); assertTrue(false); } catch (SocketException e) {}
        

        // but can still write in opposite direction.
        server.getOutputStream().write(1);
        assertEquals(1,client.getInputStream().read());
        
        // client can shutdown input
        client.shutdownInput();

        // now we EOF instead of reading -1
        try { client.getInputStream().read(); assertTrue(false); } catch (SocketException e) {}        
        
        // But we can still write at the server (data which will never be read) 
        server.getOutputStream().write(1);
        
        // and the server output is not shutdown
        assertFalse( server.isOutputShutdown() );
        
        // until we explictly shut it down
        server.shutdownOutput();
        
        // and now we can't write
        try { server.getOutputStream().write(1); assertTrue(false); } catch (SocketException e) {}
        
        // but the sockets are still open
        assertFalse(client.isClosed());
        assertFalse(server.isClosed());
        
        // but if we close one end
        client.close();

        // it is seen as closed.
        assertTrue(client.isClosed());
        
        // but not the other end
        assertFalse(server.isClosed());
        
        // which has to be closed explictly
        server.close();
        assertTrue(server.isClosed());
        
        
        
    }
}
