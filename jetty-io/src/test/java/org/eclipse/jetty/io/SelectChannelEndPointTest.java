package org.eclipse.jetty.io;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SelectChannelEndPointTest
{
    protected volatile AsyncEndPoint _lastEndp;
    protected ServerSocketChannel _connector;
    protected QueuedThreadPool _threadPool = new QueuedThreadPool();
    private int maxIdleTimeout = 600000; // TODO: use smaller value
    protected SelectorManager _manager = new SelectorManager()
    {
        @Override
        protected int getMaxIdleTime()
        {
            return maxIdleTimeout;
        }
        
        @Override
        protected void execute(Runnable task)
        {
            _threadPool.execute(task);
        }

        @Override
        protected void endPointClosed(AsyncEndPoint endpoint)
        {
        }

        @Override
        protected void endPointOpened(AsyncEndPoint endpoint)
        {
            endpoint.getAsyncConnection().onOpen();
        }

        @Override
        protected void endPointUpgraded(AsyncEndPoint endpoint, AsyncConnection oldConnection)
        {
        }

        @Override
        public AsyncConnection newConnection(SocketChannel channel, AsyncEndPoint endpoint, Object attachment)
        {
            return SelectChannelEndPointTest.this.newConnection(channel,endpoint);
        }

        @Override
        protected SelectChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key) throws IOException
        {
            SelectChannelEndPoint endp = new SelectChannelEndPoint(channel,selectSet,key,getMaxIdleTime());
            endp.setAsyncConnection(selectSet.getManager().newConnection(channel,endp, key.attachment()));
            _lastEndp=endp;
            return endp;
        }
    };

    // Must be volatile or the test may fail spuriously
    protected volatile int _blockAt=0;
    private volatile int _writeCount=1;

    @Before
    public void startManager() throws Exception
    {
        _writeCount=1;
        _lastEndp=null;
        _connector = ServerSocketChannel.open();
        _connector.socket().bind(null);
        _threadPool.start();
        _manager.start();
    }

    @After
    public void stopManager() throws Exception
    {
        _manager.stop();
        _threadPool.stop();
        _connector.close();
    }

    protected Socket newClient() throws IOException
    {
        return new Socket(_connector.socket().getInetAddress(),_connector.socket().getLocalPort());
    }

    protected AsyncConnection newConnection(SocketChannel channel, AsyncEndPoint endpoint)
    {
        return new TestConnection(endpoint);
    }

    public class TestConnection extends AbstractAsyncConnection
    {
        ByteBuffer _in = BufferUtil.allocate(32*1024);
        ByteBuffer _out = BufferUtil.allocate(32*1024);
        long _last=-1;

        public TestConnection(AsyncEndPoint endp)
        {
            super(endp,_threadPool);
        }

        @Override
        public void onOpen()
        {
            scheduleOnReadable();
        }

        @Override
        public synchronized void onReadable()
        {
            AsyncEndPoint _endp = getEndPoint();
            try
            {
                _last=System.currentTimeMillis();
                boolean progress=true;
                while(progress)
                {
                    progress=false;

                    // Fill the input buffer with everything available
                    if (BufferUtil.isFull(_in))
                        throw new IllegalStateException("FULL "+BufferUtil.toDetailString(_in));
                    int filled=_endp.fill(_in);
                    if (filled>0)
                        progress=true;

                    // If the tests wants to block, then block
                    while (_blockAt>0 && _endp.isOpen() && _in.remaining()<_blockAt)
                    {
                        FutureCallback<Void> blockingRead= new FutureCallback<>();
                        _endp.readable(null,blockingRead);
                        blockingRead.get();
                        filled=_endp.fill(_in);
                        progress|=filled>0;
                    }

                    // Copy to the out buffer
                    if (BufferUtil.hasContent(_in) && BufferUtil.append(_in,_out)>0)
                        progress=true;

                    // Blocking writes
                    if (BufferUtil.hasContent(_out))
                    {
                        ByteBuffer out=_out.duplicate();
                        BufferUtil.clear(_out);
                        for (int i=0;i<_writeCount;i++)
                        {
                            FutureCallback<Void> blockingWrite= new FutureCallback<>();
                            _endp.write(null,blockingWrite,out.asReadOnlyBuffer());
                            blockingWrite.get();
                        }
                        progress=true;
                    }

                    // are we done?
                    if (_endp.isInputShutdown())
                        _endp.shutdownOutput();
                }
            }
            catch(ExecutionException e)
            {
                // Timeout does not close, so echo exception then shutdown
                try
                {
                    FutureCallback<Void> blockingWrite= new FutureCallback<>();
                    _endp.write(null,blockingWrite,BufferUtil.toBuffer("EE: "+BufferUtil.toString(_in)));
                    blockingWrite.get();
                    _endp.shutdownOutput();
                }
                catch(Exception e2)
                {
                    e2.printStackTrace();
                }
            }
            catch(InterruptedException|EofException e)
            {
                SelectChannelEndPoint.LOG.ignore(e);
            }
            catch(Exception e)
            {
                SelectChannelEndPoint.LOG.warn(e);
            }
            finally
            {
                if (_endp.isOpen())
                    scheduleOnReadable();
            }
        }

        @Override
        public String toString()
        {
            return String.format("%s{}",
                    super.toString());
        }
    }


    @Test
    public void testEcho() throws Exception
    {
        Socket client = newClient();

        client.setSoTimeout(600000); // TODO: restore to smaller value

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);

        _manager.accept(server);

        // Write client to server
        client.getOutputStream().write("HelloWorld".getBytes("UTF-8"));

        // Verify echo server to client
        for (char c : "HelloWorld".toCharArray())
        {
            int b = client.getInputStream().read();
            assertTrue(b>0);
            assertEquals(c,(char)b);
        }

        // wait for read timeout
        client.setSoTimeout(500);
        long start=System.currentTimeMillis();
        try
        {
            client.getInputStream().read();
            Assert.fail();
        }
        catch(SocketTimeoutException e)
        {
            long duration = System.currentTimeMillis()-start;
            Assert.assertThat("timeout duration", duration, greaterThanOrEqualTo(400L));
        }

        // write then shutdown
        client.getOutputStream().write("Goodbye Cruel TLS".getBytes("UTF-8"));

        // Verify echo server to client
        for (char c : "Goodbye Cruel TLS".toCharArray())
        {
            int b = client.getInputStream().read();
            Assert.assertThat("expect valid char integer", b, greaterThan(0));
            assertEquals("expect characters to be same", c,(char)b);
        }
        client.close();

        int i=0;
        while (server.isOpen())
        {
            Thread.sleep(10);
            if (++i == 10)
                Assert.fail();
        }
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
        client.getOutputStream().write("HelloWorld".getBytes("UTF-8"));

        // Verify echo server to client
        for (char c : "HelloWorld".toCharArray())
        {
            int b = client.getInputStream().read();
            assertTrue(b>0);
            assertEquals(c,(char)b);
        }

        // wait for read timeout
        long start=System.currentTimeMillis();
        try
        {
            client.getInputStream().read();
            Assert.fail();
        }
        catch(SocketTimeoutException e)
        {
            assertTrue(System.currentTimeMillis()-start>=400);
        }

        // write then shutdown
        client.getOutputStream().write("Goodbye Cruel TLS".getBytes("UTF-8"));
        client.shutdownOutput();


        // Verify echo server to client
        for (char c : "Goodbye Cruel TLS".toCharArray())
        {
            int b = client.getInputStream().read();
            assertTrue(b>0);
            assertEquals(c,(char)b);
        }

        // Read close
        assertEquals(-1,client.getInputStream().read());

    }



    @Test
    public void testBlockRead() throws Exception
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
        _blockAt=10;
        clientOutputStream.write("12345678".getBytes("UTF-8"));
        clientOutputStream.flush();
        
        while(_lastEndp==null);

        _lastEndp.setMaxIdleTime(10*specifiedTimeout);
        Thread.sleep((11*specifiedTimeout)/10);
        
        long start=System.currentTimeMillis();
        try
        {
            int b = clientInputStream.read();
            Assert.fail("Should have timed out waiting for a response, but read "+b);
        }
        catch(SocketTimeoutException e)
        {
            int elapsed = Long.valueOf(System.currentTimeMillis() - start).intValue();
            Assert.assertThat("Expected timeout", elapsed, greaterThanOrEqualTo(3*specifiedTimeout/4));
        }
        
        // write remaining characters
        clientOutputStream.write("90ABCDEF".getBytes("UTF-8"));
        clientOutputStream.flush();

        // Verify echo server to client
        for (char c : "1234567890ABCDEF".toCharArray())
        {
            int b = clientInputStream.read();
            assertTrue(b>0);
            assertEquals(c,(char)b);
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
        client.getOutputStream().write("HelloWorld".getBytes("UTF-8"));

        // Verify echo server to client
        for (char c : "HelloWorld".toCharArray())
        {
            int b = client.getInputStream().read();
            assertTrue(b>0);
            assertEquals(c,(char)b);
        }

        // Set Max idle
        _lastEndp.setMaxIdleTime(500);

        // read until idle shutdown received
        long start=System.currentTimeMillis();
        int b=client.getInputStream().read();
        assertEquals(-1,b);
        long idle=System.currentTimeMillis()-start;
        assertTrue(idle>400);
        assertTrue(idle<2000);

        // But endpoint is still open.
        assertTrue(_lastEndp.isOpen());


        // Wait for another idle callback
        Thread.sleep(2000);
        // endpoint is closed.

        assertFalse(_lastEndp.isOpen());

    }


    @Test
    public void testBlockedReadIdle() throws Exception
    {
        Socket client = newClient();
        OutputStream clientOutputStream = client.getOutputStream();

        client.setSoTimeout(5000);

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);

        _manager.accept(server);

        // Write client to server
        clientOutputStream.write("HelloWorld".getBytes("UTF-8"));

        // Verify echo server to client
        for (char c : "HelloWorld".toCharArray())
        {
            int b = client.getInputStream().read();
            assertTrue(b>0);
            assertEquals(c,(char)b);
        }

        // Set Max idle
        _lastEndp.setMaxIdleTime(500);

        // Write 8 and cause block waiting for 10
        _blockAt=10;
        clientOutputStream.write("12345678".getBytes("UTF-8"));
        clientOutputStream.flush();

        // read until idle shutdown received
        long start=System.currentTimeMillis();
        int b=client.getInputStream().read();
        assertEquals('E',b);
        long idle=System.currentTimeMillis()-start;
        assertTrue(idle>400);
        assertTrue(idle<2000);

        for (char c : "E: 12345678".toCharArray())
        {
            b = client.getInputStream().read();
            assertTrue(b>0);
            assertEquals(c,(char)b);
        }

        // But endpoint is still open.
        assertTrue(_lastEndp.isOpen());

        // Wait for another idle callback
        Thread.sleep(2000);
        // endpoint is closed.

        assertFalse(_lastEndp.isOpen());
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

        final byte[] bytes="HelloWorld-".getBytes(StringUtil.__UTF8_CHARSET);
        byte[] count="0\n".getBytes(StringUtil.__UTF8_CHARSET);
        BufferedOutputStream out = new BufferedOutputStream(client.getOutputStream());
        final CountDownLatch latch = new CountDownLatch(writes);
        final InputStream in = new BufferedInputStream(client.getInputStream());
        final long start = System.currentTimeMillis();
        out.write(bytes);
        out.write(count);
        out.flush();

        while (_lastEndp==null)
            Thread.sleep(10);
        _lastEndp.setMaxIdleTime(5000);

        new Thread()
        {
            public void run()
            {
                Thread.currentThread().setPriority(MAX_PRIORITY);
                long last=-1;
                int count=-1;
                try
                {
                    while (latch.getCount()>0)
                    {
                        // Verify echo server to client
                        for (byte b0 : bytes)
                        {
                            int b = in.read();
                            Assert.assertThat(b,greaterThan(0));
                            assertEquals(0xff&b0,b);
                        }

                        count=0;
                        int b=in.read();
                        while(b>0 && b!='\n')
                        {
                            count=count*10+(b-'0');
                            b=in.read();
                        }
                        last=System.currentTimeMillis();

                        //if (latch.getCount()%1000==0)
                        //    System.out.println(writes-latch.getCount());
                            
                        latch.countDown();
                    }
                }
                catch(Throwable e)
                {
                    
                    long now = System.currentTimeMillis();
                    System.err.println("count="+count);
                    System.err.println("latch="+latch.getCount());
                    System.err.println("time="+(now-start));
                    System.err.println("last="+(now-last));
                    System.err.println("endp="+_lastEndp);
                    System.err.println("conn="+_lastEndp.getAsyncConnection());
                    
                    e.printStackTrace();
                }
            }
        }.start();

        // Write client to server
        for (int i=1;i<writes;i++)
        {
            out.write(bytes);
            out.write(Integer.toString(i).getBytes(StringUtil.__ISO_8859_1_CHARSET));
            out.write('\n');
            if (i%1000==0)
            {
                //System.err.println(i+"/"+writes);
                out.flush();
            }
            Thread.yield();
        }
        out.flush();

        long last=latch.getCount();
        while(!latch.await(5,TimeUnit.SECONDS))
        {
            //System.err.println(latch.getCount());
            if (latch.getCount()==last)
                Assert.fail();
            last=latch.getCount();
        }
        
        assertEquals(0,latch.getCount());
    }


    @Test
    public void testWriteBlock() throws Exception
    {
        Socket client = newClient();

        client.setSoTimeout(10000);

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);

        _manager.accept(server);

        // Write client to server
        _writeCount=10000;
        String data="Now is the time for all good men to come to the aid of the party";
        client.getOutputStream().write(data.getBytes("UTF-8"));
        BufferedInputStream in = new BufferedInputStream(client.getInputStream());

        for (int i=0;i<_writeCount;i++)
        {
            if (i%1000==0)
            {
                //System.out.println(i);
                TimeUnit.MILLISECONDS.sleep(200);
            }
            // Verify echo server to client
            for (int j=0;j<data.length();j++)
            {
                char c=data.charAt(j);
                int b = in.read();
                assertTrue(b>0);
                assertEquals("test-"+i+"/"+j,c,(char)b);
            }
            if (i==0)
                _lastEndp.setMaxIdleTime(60000);
        }


        client.close();

        int i=0;
        while (server.isOpen())
        {
            assert(i++<10);
            Thread.sleep(10);
        }

    }

}
