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
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.OS;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.junit.Assert;
import org.junit.Test;

public class IOTest
{
    @Test
    public void testIO() throws Exception
    {
        // Only a little test
        ByteArrayInputStream in = new ByteArrayInputStream("The quick brown fox jumped over the lazy dog".getBytes());
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        IO.copy(in, out);

        assertEquals("copyThread", out.toString(), "The quick brown fox jumped over the lazy dog");
    }

    @Test
    public void testHalfClose() throws Exception
    {
        ServerSocket connector = new ServerSocket(0);

        Socket client = new Socket("localhost", connector.getLocalPort());
        Socket server = connector.accept();

        // we can write both ways
        client.getOutputStream().write(1);
        assertEquals(1, server.getInputStream().read());
        server.getOutputStream().write(1);
        assertEquals(1, client.getInputStream().read());

        // shutdown output results in read -1
        client.shutdownOutput();
        assertEquals(-1, server.getInputStream().read());

        // Even though EOF has been read, the server input is not seen as shutdown
        assertFalse(server.isInputShutdown());

        // and we can read -1 again
        assertEquals(-1, server.getInputStream().read());

        // but cannot write
        try
        {
            client.getOutputStream().write(1);
            fail("exception expected");
        }
        catch (SocketException e)
        {
        }

        // but can still write in opposite direction.
        server.getOutputStream().write(1);
        assertEquals(1, client.getInputStream().read());

        // server can shutdown input to match the shutdown out of client
        server.shutdownInput();

        // now we EOF instead of reading -1
        try
        {
            server.getInputStream().read();
            fail("exception expected");
        }
        catch (SocketException e)
        {
        }

        // but can still write in opposite direction.
        server.getOutputStream().write(1);
        assertEquals(1, client.getInputStream().read());

        // client can shutdown input
        client.shutdownInput();

        // now we EOF instead of reading -1
        try
        {
            client.getInputStream().read();
            fail("exception expected");
        }
        catch (SocketException e)
        {
        }

        // But we can still write at the server (data which will never be read)
        server.getOutputStream().write(1);

        // and the server output is not shutdown
        assertFalse(server.isOutputShutdown());

        // until we explictly shut it down
        server.shutdownOutput();

        // and now we can't write
        try
        {
            server.getOutputStream().write(1);
            fail("exception expected");
        }
        catch (SocketException e)
        {
        }

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
        client.setSoLinger(false, -1);
        Socket server = connector.accept().socket();
        server.setSoTimeout(1000);
        server.setSoLinger(false, -1);

        // Write from client to server
        client.getOutputStream().write(1);

        // Server reads
        assertEquals(1, server.getInputStream().read());

        // Write from server to client with oshut
        server.getOutputStream().write(1);
        // System.err.println("OSHUT "+server);
        server.shutdownOutput();

        // Client reads response
        assertEquals(1, client.getInputStream().read());

        try
        {
            // Client reads -1 and does ishut
            assertEquals(-1, client.getInputStream().read());
            assertFalse(client.isInputShutdown());
            //System.err.println("ISHUT "+client);
            client.shutdownInput();

            // Client ???
            //System.err.println("OSHUT "+client);
            client.shutdownOutput();
            //System.err.println("CLOSE "+client);
            client.close();

            // Server reads -1, does ishut and then close
            assertEquals(-1, server.getInputStream().read());
            assertFalse(server.isInputShutdown());
            //System.err.println("ISHUT "+server);

            try
            {
                server.shutdownInput();
            }
            catch (SocketException e)
            {
                // System.err.println(e);
            }
            //System.err.println("CLOSE "+server);
            server.close();

        }
        catch (Exception e)
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
        client.setSoLinger(false, -1);
        Socket server = connector.accept().socket();
        server.setSoTimeout(1000);
        server.setSoLinger(false, -1);

        // Write from client to server
        client.getOutputStream().write(1);

        // Server reads
        assertEquals(1, server.getInputStream().read());

        // Write from server to client with oshut
        server.getOutputStream().write(1);
        //System.err.println("OSHUT "+server);
        server.shutdownOutput();

        try
        {
            // Client reads response
            assertEquals(1, client.getInputStream().read());

            // Client reads -1
            assertEquals(-1, client.getInputStream().read());
            assertFalse(client.isInputShutdown());

            // Client can still write as we are half closed
            client.getOutputStream().write(1);

            // Server can still read
            assertEquals(1, server.getInputStream().read());

            // Server now closes
            server.close();

            // Client still reads -1 (not broken pipe !!)
            assertEquals(-1, client.getInputStream().read());
            assertFalse(client.isInputShutdown());

            Thread.sleep(100);

            // Client still reads -1 (not broken pipe !!)
            assertEquals(-1, client.getInputStream().read());
            assertFalse(client.isInputShutdown());

            // Client can still write data even though server is closed???
            client.getOutputStream().write(1);

            // Client eventually sees Broken Pipe
            int i = 0;
            try
            {
                for (i = 0; i < 100000; i++)
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
    public void testServerChannelInterrupt() throws Exception
    {
        final ServerSocketChannel connector = ServerSocketChannel.open();
        connector.configureBlocking(true);
        connector.socket().bind(null);

        Socket client = SocketChannel.open(connector.socket().getLocalSocketAddress()).socket();
        client.setSoTimeout(2000);
        client.setSoLinger(false, -1);
        Socket server = connector.accept().socket();
        server.setSoTimeout(2000);
        server.setSoLinger(false, -1);

        // Write from client to server
        client.getOutputStream().write(1);
        // Server reads
        assertEquals(1, server.getInputStream().read());

        // Write from server to client
        server.getOutputStream().write(1);
        // Client reads
        assertEquals(1, client.getInputStream().read());


        // block a thread in accept
        final CountDownLatch alatch=new CountDownLatch(2);
        Thread acceptor = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    alatch.countDown();
                    connector.accept();
                }
                catch (Throwable e)
                {
                }
                finally
                {
                    alatch.countDown();
                }
            }
        };
        acceptor.start();
        while (alatch.getCount()==2)
            Thread.sleep(10);

        // interrupt the acceptor
        acceptor.interrupt();

        // wait for acceptor to exit
        assertTrue(alatch.await(10,TimeUnit.SECONDS));

        // connector is closed
        assertFalse(connector.isOpen());

        // but connection is still open
        assertFalse(client.isClosed());
        assertFalse(server.isClosed());

        // Write from client to server
        client.getOutputStream().write(42);
        // Server reads
        assertEquals(42, server.getInputStream().read());

        // Write from server to client
        server.getOutputStream().write(43);
        // Client reads
        assertEquals(43, client.getInputStream().read());

        client.close();

    }



    @Test
    public void testReset() throws Exception
    {
        try (ServerSocket connector = new ServerSocket(0);
            Socket client = new Socket("127.0.0.1", connector.getLocalPort());
            Socket server = connector.accept();)
        {
            client.setTcpNoDelay(true);
            client.setSoLinger(true, 0);
            server.setTcpNoDelay(true);
            server.setSoLinger(true, 0);

            client.getOutputStream().write(1);
            assertEquals(1, server.getInputStream().read());
            server.getOutputStream().write(1);
            assertEquals(1, client.getInputStream().read());

            // Server generator shutdowns output after non persistent sending response.
            server.shutdownOutput();

            // client endpoint reads EOF and shutdown input as result
            assertEquals(-1, client.getInputStream().read());
            client.shutdownInput();

            // client connection see's EOF and shutsdown output as no more requests to be sent.
            client.shutdownOutput();

            // Since input already shutdown, client also closes socket.
            client.close();

            // Server reads the EOF from client oshut and shut's down it's input
            assertEquals(-1, server.getInputStream().read());
            server.shutdownInput();

            // Since output was already shutdown, server closes
            server.close();
        }
    }

    @Test
    public void testAsyncSocketChannel() throws Exception
    {
        AsynchronousServerSocketChannel connector = AsynchronousServerSocketChannel.open();
        connector.bind(null);
        InetSocketAddress addr=(InetSocketAddress)connector.getLocalAddress();
        Future<AsynchronousSocketChannel> acceptor = connector.accept();

        AsynchronousSocketChannel client = AsynchronousSocketChannel.open();

        client.connect(new InetSocketAddress("127.0.0.1",addr.getPort())).get(5, TimeUnit.SECONDS);

        AsynchronousSocketChannel server = acceptor.get(5, TimeUnit.SECONDS);

        ByteBuffer read = ByteBuffer.allocate(1024);
        Future<Integer> reading = server.read(read);

        byte[] data = "Testing 1 2 3".getBytes(StandardCharsets.UTF_8);
        ByteBuffer write = BufferUtil.toBuffer(data);
        Future<Integer> writing = client.write(write);

        writing.get(5, TimeUnit.SECONDS);
        reading.get(5, TimeUnit.SECONDS);
        read.flip();

        Assert.assertEquals(ByteBuffer.wrap(data), read);
    }

    @Test
    public void testGatherWrite() throws Exception
    {
        File dir = MavenTestingUtils.getTargetTestingDir();
        if (!dir.exists())
            dir.mkdir();

        File file = File.createTempFile("test",".txt",dir);
        file.deleteOnExit();
        FileChannel out = FileChannel.open(file.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
                StandardOpenOption.DELETE_ON_CLOSE);

        ByteBuffer[] buffers = new ByteBuffer[4096];
        long expected=0;
        for (int i=0;i<buffers.length;i++)
        {
            buffers[i]=BufferUtil.toBuffer(i);
            expected+=buffers[i].remaining();
        }

        long wrote = IO.write(out,buffers,0,buffers.length);

        assertEquals(expected,wrote);

        for (int i=0;i<buffers.length;i++)
            assertEquals(0,buffers[i].remaining());
    }
}
