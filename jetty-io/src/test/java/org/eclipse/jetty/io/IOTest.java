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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import junit.framework.Assert;

import org.eclipse.jetty.toolchain.test.OS;
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
        // System.err.println(out);

        assertEquals( "copyThread",
                      out.toString(),
                      "The quick brown fox jumped over the lazy dog");
    }
    
    @Test
    public void testHalfClose() throws Exception
    {
        ServerSocket connector = new ServerSocket(0);
        
        Socket client = new Socket("localhost",connector.getLocalPort());
        Socket server = connector.accept();
        
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
        try { client.getOutputStream().write(1); fail("exception expected"); } catch (SocketException e) {}
   
        // but can still write in opposite direction.
        server.getOutputStream().write(1);
        assertEquals(1,client.getInputStream().read());
   
        
        // server can shutdown input to match the shutdown out of client
        server.shutdownInput();
        
        // now we EOF instead of reading -1
        try { server.getInputStream().read(); fail("exception expected"); } catch (SocketException e) {}
        

        // but can still write in opposite direction.
        server.getOutputStream().write(1);
        assertEquals(1,client.getInputStream().read());
        
        // client can shutdown input
        client.shutdownInput();

        // now we EOF instead of reading -1
        try { client.getInputStream().read(); fail("exception expected"); } catch (SocketException e) {}        
        
        // But we can still write at the server (data which will never be read) 
        server.getOutputStream().write(1);
        
        // and the server output is not shutdown
        assertFalse( server.isOutputShutdown() );
        
        // until we explictly shut it down
        server.shutdownOutput();
        
        // and now we can't write
        try { server.getOutputStream().write(1); fail("exception expected"); } catch (SocketException e) {}
        
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

    
    @Test
    public void testHalfCloseClientServer() throws Exception
    {
        ServerSocketChannel connector = ServerSocketChannel.open();
        connector.socket().bind(null);
        
        Socket client = SocketChannel.open(connector.socket().getLocalSocketAddress()).socket();
        client.setSoTimeout(1000);
        client.setSoLinger(false,-1);
        Socket server = connector.accept().socket();
        server.setSoTimeout(1000);
        server.setSoLinger(false,-1);
        
        // Write from client to server
        client.getOutputStream().write(1);
        
        // Server reads 
        assertEquals(1,server.getInputStream().read());

        // Write from server to client with oshut
        server.getOutputStream().write(1);
        // System.err.println("OSHUT "+server);
        server.shutdownOutput();

        // Client reads response
        assertEquals(1,client.getInputStream().read());

        try
        {
            // Client reads -1 and does ishut
            assertEquals(-1,client.getInputStream().read());
            assertFalse(client.isInputShutdown());
            //System.err.println("ISHUT "+client);
            client.shutdownInput();

            // Client ???
            //System.err.println("OSHUT "+client);
            client.shutdownOutput();
            //System.err.println("CLOSE "+client);
            client.close();

            // Server reads -1, does ishut and then close
            assertEquals(-1,server.getInputStream().read());
            assertFalse(server.isInputShutdown());
            //System.err.println("ISHUT "+server);

            try
            {
                server.shutdownInput();
            }
            catch(SocketException e)
            {
                // System.err.println(e);
            }
            //System.err.println("CLOSE "+server);
            server.close();

        }
        catch(Exception e)
        {
            System.err.println(e);
            assertTrue(OS.IS_OSX);
        }
    }

    @Test
    public void testHalfCloseBadClient() throws Exception
    {
        ServerSocketChannel connector = ServerSocketChannel.open();
        connector.socket().bind(null);
        
        Socket client = SocketChannel.open(connector.socket().getLocalSocketAddress()).socket();
        client.setSoTimeout(1000);
        client.setSoLinger(false,-1);
        Socket server = connector.accept().socket();
        server.setSoTimeout(1000);
        server.setSoLinger(false,-1);
        
        // Write from client to server
        client.getOutputStream().write(1);
        
        // Server reads 
        assertEquals(1,server.getInputStream().read());

        // Write from server to client with oshut
        server.getOutputStream().write(1);
        //System.err.println("OSHUT "+server);
        server.shutdownOutput();

        try
        {
            // Client reads response
            assertEquals(1,client.getInputStream().read());

            // Client reads -1 
            assertEquals(-1,client.getInputStream().read());
            assertFalse(client.isInputShutdown());

            // Client can still write as we are half closed
            client.getOutputStream().write(1);

            // Server can still read 
            assertEquals(1,server.getInputStream().read());

            // Server now closes 
            server.close();

            // Client still reads -1 (not broken pipe !!)
            assertEquals(-1,client.getInputStream().read());
            assertFalse(client.isInputShutdown());

            Thread.sleep(100);

            // Client still reads -1 (not broken pipe !!)
            assertEquals(-1,client.getInputStream().read());
            assertFalse(client.isInputShutdown());

            // Client can still write data even though server is closed???
            client.getOutputStream().write(1);

            // Client eventually sees Broken Pipe
            int i=0;
            try
            {
                for (i=0;i<100000;i++)
                    client.getOutputStream().write(1);

                Assert.fail();
            }
            catch (IOException e)
            {
            }
            client.close();

        }
        catch (Exception e)
        {
            System.err.println("PLEASE INVESTIGATE:");
            e.printStackTrace();
        }
    }

    @Test
    public void testReset() throws Exception
    {
        ServerSocket connector;
        Socket client;
        Socket server;
       
        connector = new ServerSocket(9123);
        client = new Socket("127.0.0.1",connector.getLocalPort());
        server = connector.accept();
        client.setTcpNoDelay(true);
        client.setSoLinger(true,0);
        server.setTcpNoDelay(true);
        server.setSoLinger(true,0);
       
        client.getOutputStream().write(1);
        assertEquals(1,server.getInputStream().read());
        server.getOutputStream().write(1);
        assertEquals(1,client.getInputStream().read());
       
        // Server generator shutdowns output after non persistent sending response.
        server.shutdownOutput();
       
        // client endpoint reads EOF and shutdown input as result
        assertEquals(-1,client.getInputStream().read());
        client.shutdownInput();
       
        // client connection see's EOF and shutsdown output as no more requests to be sent.
        client.shutdownOutput();
       
        // Since input already shutdown, client also closes socket.
        client.close();
       
        // Server reads the EOF from client oshut and shut's down it's input
        assertEquals(-1,server.getInputStream().read());
        server.shutdownInput();
       
        // Since output was already shutdown, server closes
        server.close();
    }

}
