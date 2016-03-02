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

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.TimerScheduler;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

public class SelectChannelEndPointTest
{
    private static final Logger LOG = Log.getLogger(SelectChannelEndPointTest.class);
    protected CountDownLatch _lastEndPointLatch;
    protected volatile EndPoint _lastEndPoint;
    protected ServerSocketChannel _connector;
    protected QueuedThreadPool _threadPool = new QueuedThreadPool();
    protected Scheduler _scheduler = new TimerScheduler();
    protected SelectorManager _manager = new SelectorManager(_threadPool, _scheduler)
    {
        @Override
        public Connection newConnection(SelectableChannel channel, EndPoint endpoint, Object attachment)
        {
            return SelectChannelEndPointTest.this.newConnection(channel, endpoint);
        }

        @Override
        protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey key) throws IOException
        {
            SocketChannelEndPoint endp = new SocketChannelEndPoint(channel, selector, key, getScheduler());
            endp.setIdleTimeout(60000);
            _lastEndPoint = endp;
            _lastEndPointLatch.countDown();
            return endp;
        }
        
    };

    // Must be volatile or the test may fail spuriously
    protected volatile int _blockAt = 0;
    private volatile int _writeCount = 1;

    @Before
    public void startManager() throws Exception
    {
        _writeCount = 1;
        _lastEndPoint = null;
        _lastEndPointLatch = new CountDownLatch(1);
        _connector = ServerSocketChannel.open();
        _connector.socket().bind(null);
        _scheduler.start();
        _threadPool.start();
        _manager.start();
    }

    @After
    public void stopManager() throws Exception
    {
        _scheduler.stop();
        _manager.stop();
        _threadPool.stop();
        _connector.close();
    }

    protected Socket newClient() throws IOException
    {
        return new Socket(_connector.socket().getInetAddress(), _connector.socket().getLocalPort());
    }

    protected Connection newConnection(SelectableChannel channel, EndPoint endpoint)
    {
        return new TestConnection(endpoint);
    }

    public class TestConnection extends AbstractConnection
    {
        volatile FutureCallback _blockingRead;
        ByteBuffer _in = BufferUtil.allocate(32 * 1024);
        ByteBuffer _out = BufferUtil.allocate(32 * 1024);
        long _last = -1;
        final CountDownLatch _latch;

        public TestConnection(EndPoint endp)
        {
            super(endp, _threadPool);
            _latch=null;
        }
        
        public TestConnection(EndPoint endp,CountDownLatch latch)
        {
            super(endp, _threadPool);
            _latch=latch;
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            fillInterested();
        }

        @Override
        public void onFillInterestedFailed(Throwable cause)
        {
            Callback blocking = _blockingRead;
            if (blocking!=null)
            {
                _blockingRead=null;
                blocking.failed(cause);
                return;
            }
            super.onFillInterestedFailed(cause);
        }
        
        @Override
        public void onFillable()
        {
            if (_latch!=null)
            {
                try
                {
                    _latch.await();
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
            }
            
            Callback blocking = _blockingRead;
            if (blocking!=null)
            {
                _blockingRead=null;
                blocking.succeeded();
                return;
            }
            
            EndPoint _endp = getEndPoint();
            try
            {
                _last = System.currentTimeMillis();
                boolean progress = true;
                while (progress)
                {
                    progress = false;

                    // Fill the input buffer with everything available
                    BufferUtil.compact(_in);
                    if (BufferUtil.isFull(_in))
                        throw new IllegalStateException("FULL " + BufferUtil.toDetailString(_in));
                    int filled = _endp.fill(_in);
                    if (filled > 0)
                        progress = true;

                    // If the tests wants to block, then block
                    while (_blockAt > 0 && _endp.isOpen() && _in.remaining() < _blockAt)
                    {
                        FutureCallback future = _blockingRead = new FutureCallback();
                        fillInterested();
                        future.get();
                        filled = _endp.fill(_in);
                        progress |= filled > 0;
                    }

                    // Copy to the out buffer
                    if (BufferUtil.hasContent(_in) && BufferUtil.append(_out, _in) > 0)
                        progress = true;

                    // Blocking writes
                    if (BufferUtil.hasContent(_out))
                    {
                        ByteBuffer out = _out.duplicate();
                        BufferUtil.clear(_out);
                        for (int i = 0; i < _writeCount; i++)
                        {
                            FutureCallback blockingWrite = new FutureCallback();
                            _endp.write(blockingWrite, out.asReadOnlyBuffer());
                            blockingWrite.get();
                        }
                        progress = true;
                    }

                    // are we done?
                    if (_endp.isInputShutdown())
                        _endp.shutdownOutput();
                }

                if (_endp.isOpen())
                    fillInterested();
            }
            catch (ExecutionException e)
            {
                // Timeout does not close, so echo exception then shutdown
                try
                {
                    FutureCallback blockingWrite = new FutureCallback();
                    _endp.write(blockingWrite, BufferUtil.toBuffer("EE: " + BufferUtil.toString(_in)));
                    blockingWrite.get();
                    _endp.shutdownOutput();
                }
                catch (Exception e2)
                {
                    // e2.printStackTrace();
                }
            }
            catch (InterruptedException | EofException e)
            {
                Log.getRootLogger().ignore(e);
            }
            catch (Exception e)
            {
                Log.getRootLogger().warn(e);
            }
            finally
            {
            }
        }
    }

    @Test
    public void testEcho() throws Exception
    {
        Socket client = newClient();

        client.setSoTimeout(60000);

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);

        _manager.accept(server);

        // Write client to server
        client.getOutputStream().write("HelloWorld".getBytes(StandardCharsets.UTF_8));

        // Verify echo server to client
        for (char c : "HelloWorld".toCharArray())
        {
            int b = client.getInputStream().read();
            assertTrue(b > 0);
            assertEquals(c, (char)b);
        }

        // wait for read timeout
        client.setSoTimeout(500);
        long start = System.currentTimeMillis();
        try
        {
            client.getInputStream().read();
            Assert.fail();
        }
        catch (SocketTimeoutException e)
        {
            long duration = System.currentTimeMillis() - start;
            Assert.assertThat("timeout duration", duration, greaterThanOrEqualTo(400L));
        }

        // write then shutdown
        client.getOutputStream().write("Goodbye Cruel TLS".getBytes(StandardCharsets.UTF_8));

        // Verify echo server to client
        for (char c : "Goodbye Cruel TLS".toCharArray())
        {
            int b = client.getInputStream().read();
            Assert.assertThat("expect valid char integer", b, greaterThan(0));
            assertEquals("expect characters to be same", c, (char)b);
        }
        client.close();

        for (int i = 0; i < 10; ++i)
        {
            if (server.isOpen())
                Thread.sleep(10);
            else
                break;
        }
        assertFalse(server.isOpen());
    }

    @Test
    public void testShutdown() throws Exception
    {
        Socket client = newClient();

        client.setSoTimeout(500);

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);

        _manager.accept(server);

        // Write client to server
        client.getOutputStream().write("HelloWorld".getBytes(StandardCharsets.UTF_8));

        // Verify echo server to client
        for (char c : "HelloWorld".toCharArray())
        {
            int b = client.getInputStream().read();
            assertTrue(b > 0);
            assertEquals(c, (char)b);
        }

        // wait for read timeout
        long start = System.currentTimeMillis();
        try
        {
            client.getInputStream().read();
            Assert.fail();
        }
        catch (SocketTimeoutException e)
        {
            assertTrue(System.currentTimeMillis() - start >= 400);
        }

        // write then shutdown
        client.getOutputStream().write("Goodbye Cruel TLS".getBytes(StandardCharsets.UTF_8));
        client.shutdownOutput();

        // Verify echo server to client
        for (char c : "Goodbye Cruel TLS".toCharArray())
        {
            int b = client.getInputStream().read();
            assertTrue(b > 0);
            assertEquals(c, (char)b);
        }

        // Read close
        assertEquals(-1, client.getInputStream().read());
    }

    @Test
    public void testReadBlocked() throws Exception
    {
        Socket client = newClient();

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);

        _manager.accept(server);

        OutputStream clientOutputStream = client.getOutputStream();
        InputStream clientInputStream = client.getInputStream();

        int specifiedTimeout = 1000;
        client.setSoTimeout(specifiedTimeout);

        // Write 8 and cause block waiting for 10
        _blockAt = 10;
        clientOutputStream.write("12345678".getBytes(StandardCharsets.UTF_8));
        clientOutputStream.flush();

        Assert.assertTrue(_lastEndPointLatch.await(1, TimeUnit.SECONDS));
        _lastEndPoint.setIdleTimeout(10 * specifiedTimeout);
        Thread.sleep((11 * specifiedTimeout) / 10);

        long start = System.currentTimeMillis();
        try
        {
            int b = clientInputStream.read();
            Assert.fail("Should have timed out waiting for a response, but read " + b);
        }
        catch (SocketTimeoutException e)
        {
            int elapsed = Long.valueOf(System.currentTimeMillis() - start).intValue();
            Assert.assertThat("Expected timeout", elapsed, greaterThanOrEqualTo(3 * specifiedTimeout / 4));
        }

        // write remaining characters
        clientOutputStream.write("90ABCDEF".getBytes(StandardCharsets.UTF_8));
        clientOutputStream.flush();

        // Verify echo server to client
        for (char c : "1234567890ABCDEF".toCharArray())
        {
            int b = clientInputStream.read();
            assertTrue(b > 0);
            assertEquals(c, (char)b);
        }
    }

    @Test
    public void testIdle() throws Exception
    {
        Socket client = newClient();

        client.setSoTimeout(3000);

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);

        _manager.accept(server);

        // Write client to server
        client.getOutputStream().write("HelloWorld".getBytes(StandardCharsets.UTF_8));

        // Verify echo server to client
        for (char c : "HelloWorld".toCharArray())
        {
            int b = client.getInputStream().read();
            assertTrue(b > 0);
            assertEquals(c, (char)b);
        }

        Assert.assertTrue(_lastEndPointLatch.await(1, TimeUnit.SECONDS));
        int idleTimeout = 500;
        _lastEndPoint.setIdleTimeout(idleTimeout);

        // read until idle shutdown received
        long start = System.currentTimeMillis();
        int b = client.getInputStream().read();
        assertEquals(-1, b);
        long idle = System.currentTimeMillis() - start;
        assertTrue(idle > idleTimeout / 2);
        assertTrue(idle < idleTimeout * 2);

        // But endpoint may still be open for a little bit.
        for (int i = 0; i < 10; ++i)
        {
            if (_lastEndPoint.isOpen())
                Thread.sleep(2 * idleTimeout / 10);
            else
                break;
        }
        assertFalse(_lastEndPoint.isOpen());
    }

    @Test
    public void testBlockedReadIdle() throws Exception
    {
        Socket client = newClient();
        InputStream clientInputStream = client.getInputStream();
        OutputStream clientOutputStream = client.getOutputStream();

        client.setSoTimeout(5000);

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);

        _manager.accept(server);

        // Write client to server
        clientOutputStream.write("HelloWorld".getBytes(StandardCharsets.UTF_8));

        // Verify echo server to client
        for (char c : "HelloWorld".toCharArray())
        {
            int b = clientInputStream.read();
            assertTrue(b > 0);
            assertEquals(c, (char)b);
        }

        Assert.assertTrue(_lastEndPointLatch.await(1, TimeUnit.SECONDS));
        int idleTimeout = 500;
        _lastEndPoint.setIdleTimeout(idleTimeout);

        // Write 8 and cause block waiting for 10
        _blockAt = 10;
        clientOutputStream.write("12345678".getBytes(StandardCharsets.UTF_8));
        clientOutputStream.flush();

        // read until idle shutdown received
        long start = System.currentTimeMillis();
        int b = clientInputStream.read();
        assertEquals('E', b);
        long idle = System.currentTimeMillis() - start;
        assertTrue(idle > idleTimeout / 2);
        assertTrue(idle < idleTimeout * 2);

        for (char c : "E: 12345678".toCharArray())
        {
            b = clientInputStream.read();
            assertTrue(b > 0);
            assertEquals(c, (char)b);
        }
        b = clientInputStream.read();
        assertEquals(-1,b);

        // But endpoint is still open.
        if(_lastEndPoint.isOpen())
            // Wait for another idle callback
            Thread.sleep(idleTimeout * 2);

        // endpoint is closed.
        assertFalse(_lastEndPoint.isOpen());
    }

    @Test
    public void testStress() throws Exception
    {
        Socket client = newClient();
        client.setSoTimeout(30000);

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);

        _manager.accept(server);
        final int writes = 200000;

        final byte[] bytes = "HelloWorld-".getBytes(StandardCharsets.UTF_8);
        byte[] count = "0\n".getBytes(StandardCharsets.UTF_8);
        BufferedOutputStream out = new BufferedOutputStream(client.getOutputStream());
        final CountDownLatch latch = new CountDownLatch(writes);
        final InputStream in = new BufferedInputStream(client.getInputStream());
        final long start = System.currentTimeMillis();
        out.write(bytes);
        out.write(count);
        out.flush();

        Assert.assertTrue(_lastEndPointLatch.await(1, TimeUnit.SECONDS));
        _lastEndPoint.setIdleTimeout(5000);

        new Thread()
        {
            @Override
            public void run()
            {
                Thread.currentThread().setPriority(MAX_PRIORITY);
                long last = -1;
                int count = -1;
                try
                {
                    while (latch.getCount() > 0)
                    {
                        // Verify echo server to client
                        for (byte b0 : bytes)
                        {
                            int b = in.read();
                            Assert.assertThat(b, greaterThan(0));
                            assertEquals(0xff & b0, b);
                        }

                        count = 0;
                        int b = in.read();
                        while (b > 0 && b != '\n')
                        {
                            count = count * 10 + (b - '0');
                            b = in.read();
                        }
                        last = System.currentTimeMillis();

                        //if (latch.getCount()%1000==0)
                        //    System.out.println(writes-latch.getCount());

                        latch.countDown();
                    }
                }
                catch (Throwable e)
                {

                    long now = System.currentTimeMillis();
                    System.err.println("count=" + count);
                    System.err.println("latch=" + latch.getCount());
                    System.err.println("time=" + (now - start));
                    System.err.println("last=" + (now - last));
                    System.err.println("endp=" + _lastEndPoint);
                    System.err.println("conn=" + _lastEndPoint.getConnection());

                    e.printStackTrace();
                }
            }
        }.start();

        // Write client to server
        for (int i = 1; i < writes; i++)
        {
            out.write(bytes);
            out.write(Integer.toString(i).getBytes(StandardCharsets.ISO_8859_1));
            out.write('\n');
            if (i % 1000 == 0)
            {
                //System.err.println(i+"/"+writes);
                out.flush();
            }
            Thread.yield();
        }
        out.flush();

        long last = latch.getCount();
        while (!latch.await(5, TimeUnit.SECONDS))
        {
            //System.err.println(latch.getCount());
            if (latch.getCount() == last)
                Assert.fail();
            last = latch.getCount();
        }

        assertEquals(0, latch.getCount());
    }

    @Test
    public void testWriteBlocked() throws Exception
    {
        Socket client = newClient();

        client.setSoTimeout(10000);

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);

        _manager.accept(server);

        // Write client to server
        _writeCount = 10000;
        String data = "Now is the time for all good men to come to the aid of the party";
        client.getOutputStream().write(data.getBytes(StandardCharsets.UTF_8));
        BufferedInputStream in = new BufferedInputStream(client.getInputStream());

        int byteNum = 0;
        try
        {
            for (int i = 0; i < _writeCount; i++)
            {
                if (i % 1000 == 0)
                    TimeUnit.MILLISECONDS.sleep(200);

                // Verify echo server to client
                for (int j = 0; j < data.length(); j++)
                {
                    char c = data.charAt(j);
                    int b = in.read();
                    byteNum++;
                    assertTrue(b > 0);
                    assertEquals("test-" + i + "/" + j,c,(char)b);
                }

                if (i == 0)
                    _lastEndPoint.setIdleTimeout(60000);
            }
        }
        catch (SocketTimeoutException e)
        {
            System.err.println("SelectorManager.dump() = " + _manager.dump());
            LOG.warn("Server: " + server);
            LOG.warn("Error reading byte #" + byteNum,e);
            throw e;
        }

        client.close();

        for (int i = 0; i < 10; ++i)
        {
            if (server.isOpen())
                Thread.sleep(10);
            else
                break;
        }
        assertFalse(server.isOpen());
    }
    

    // TODO make this test reliable
    @Test
    @Ignore
    public void testRejectedExecution() throws Exception
    {
        _manager.stop();
        _threadPool.stop();
        
        final CountDownLatch latch = new CountDownLatch(1);
        
        BlockingQueue<Runnable> q = new ArrayBlockingQueue<>(4);
        _threadPool = new QueuedThreadPool(4,4,60000,q);
        _manager = new SelectorManager(_threadPool, _scheduler, 1)
        {

            @Override
            protected EndPoint newEndPoint(SelectableChannel channel, ManagedSelector selector, SelectionKey selectionKey) throws IOException
            {
                SocketChannelEndPoint endp = new SocketChannelEndPoint(channel,selector,selectionKey,getScheduler());
                _lastEndPoint = endp;
                _lastEndPointLatch.countDown();
                return endp;
            }

            @Override
            public Connection newConnection(SelectableChannel channel, EndPoint endpoint, Object attachment) throws IOException
            {
                return new TestConnection(endpoint,latch);
            }
        };
        
        _threadPool.start();
        _manager.start();
        
        AtomicInteger timeout = new AtomicInteger();
        AtomicInteger rejections = new AtomicInteger();
        AtomicInteger echoed = new AtomicInteger();
        
        CountDownLatch closed = new CountDownLatch(20);
        for (int i=0;i<20;i++)
        {
            new Thread()
            {
                public void run()
                {
                    try(Socket client = newClient();)
                    {
                        client.setSoTimeout(5000);

                        SocketChannel server = _connector.accept();
                        server.configureBlocking(false);

                        _manager.accept(server);

                        // Write client to server
                        client.getOutputStream().write("HelloWorld".getBytes(StandardCharsets.UTF_8));
                        client.getOutputStream().flush();
                        client.shutdownOutput();

                        // Verify echo server to client
                        for (char c : "HelloWorld".toCharArray())
                        {
                            int b = client.getInputStream().read();
                            assertTrue(b > 0);
                            assertEquals(c, (char)b);
                        }
                        assertEquals(-1,client.getInputStream().read());
                        echoed.incrementAndGet();
                    }
                    catch(SocketTimeoutException x)
                    {
                        x.printStackTrace();
                        timeout.incrementAndGet();
                    }
                    catch(Throwable x)
                    {
                        rejections.incrementAndGet();
                    }
                    finally
                    {
                        closed.countDown();
                    }
                }
            }.start();
        }

        // unblock the handling
        latch.countDown();
        
        // wait for all clients to complete or fail
        closed.await();
        
        // assert some clients must have been rejected
        Assert.assertThat(rejections.get(),Matchers.greaterThan(0));
        // but not all of them
        Assert.assertThat(rejections.get(),Matchers.lessThan(20));
        // none should have timed out
        Assert.assertThat(timeout.get(),Matchers.equalTo(0));
        // and the rest should have worked
        Assert.assertThat(echoed.get(),Matchers.equalTo(20-rejections.get())); 
        
        // and the selector is still working for new requests
        try(Socket client = newClient();)
        {
            client.setSoTimeout(5000);

            SocketChannel server = _connector.accept();
            server.configureBlocking(false);

            _manager.accept(server);

            // Write client to server
            client.getOutputStream().write("HelloWorld".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            client.shutdownOutput();

            // Verify echo server to client
            for (char c : "HelloWorld".toCharArray())
            {
                int b = client.getInputStream().read();
                assertTrue(b > 0);
                assertEquals(c, (char)b);
            }
            assertEquals(-1,client.getInputStream().read());
        }
        
    }
}
