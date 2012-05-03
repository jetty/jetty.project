package org.eclipse.jetty.io;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.AbstractAsyncConnection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SelectChannelEndPointTest
{
    protected volatile AsyncEndPoint _lastEndp;
    protected ServerSocketChannel _connector;
    protected QueuedThreadPool _threadPool = new QueuedThreadPool();
    protected SelectorManager _manager = new SelectorManager()
    {
        @Override
        public boolean dispatch(Runnable task)
        {
            return _threadPool.dispatch(task);
        }

        @Override
        protected void endPointClosed(SelectChannelEndPoint endpoint)
        {
        }

        @Override
        protected void endPointOpened(SelectChannelEndPoint endpoint)
        {
        }

        @Override
        protected void endPointUpgraded(SelectChannelEndPoint endpoint, AsyncConnection oldConnection)
        {
        }

        @Override
        public AbstractAsyncConnection newConnection(SocketChannel channel, SelectChannelEndPoint endpoint, Object attachment)
        {
            AbstractAsyncConnection connection = SelectChannelEndPointTest.this.newConnection(channel,endpoint);
            return connection;
        }

        @Override
        protected SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectSet selectSet, SelectionKey key) throws IOException
        {
            SelectChannelEndPoint endp = new SelectChannelEndPoint(channel,selectSet,key,2000);
            endp.setAsyncConnection(selectSet.getManager().newConnection(channel,endp, key.attachment()));
            _lastEndp=endp;
            return endp;
        }
    };

    // Must be volatile or the test may fail spuriously
    private volatile int _blockAt=0;
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

    protected AbstractAsyncConnection newConnection(SocketChannel channel, AsyncEndPoint endpoint)
    {
        AbstractAsyncConnection connection = new TestConnection(endpoint);
        connection.scheduleOnReadable();
        return connection;
    }

    public class TestConnection extends AbstractAsyncConnection
    {
        ByteBuffer _in = BufferUtil.allocate(32*1024);
        ByteBuffer _out = BufferUtil.allocate(32*1024);

        public TestConnection(AsyncEndPoint endp)
        {
            super(endp);
        }

        @Override
        public void onReadable()
        {
            try
            {
                _endp.setCheckForIdle(false);
                boolean progress=true;
                while(progress)
                {
                    progress=false;

                    // Fill the input buffer with everything available
                    if (!BufferUtil.isFull(_in))
                        progress|=_endp.fill(_in)>0;
                        
                    // If the tests wants to block, then block
                    while (_blockAt>0 && _endp.isOpen() && _in.remaining()<_blockAt)
                    {
                        _endp.read().block();
                        int filled=_endp.fill(_in);
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
                            _endp.write(out.asReadOnlyBuffer()).block();
                        }
                        progress=true;
                    }
                }
            }
            catch(ClosedChannelException e)
            {
                // System.err.println(e);
            }
            catch(ExecutionException e)
            {
                // Timeout does not close, so echo exception then shutdown
                try
                {
                    _endp.write(BufferUtil.toBuffer("EE: "+BufferUtil.toString(_in))).block();
                    _endp.shutdownOutput();
                }
                catch(Exception e2)
                {
                    e2.printStackTrace();
                }
            }
            catch(InterruptedException e)
            {
                // System.err.println(e);
            }
            catch(Exception e)
            {
                e.printStackTrace();
            }
            finally
            {
                if (_endp.isOpen())
                {
                    _endp.setCheckForIdle(true);
                    scheduleOnReadable();
                }
            }
        }


        @Override
        public void onInputShutdown()
        {
            try
            {
                if (BufferUtil.isEmpty(_out))
                    _endp.shutdownOutput();
            }
            catch(IOException e)
            {
                e.printStackTrace();
            }
        }

        @Override
        public void onClose()
        {            
        }
        
        
    }

    
    @Test
    public void testEcho() throws Exception
    {
        Socket client = newClient();

        client.setSoTimeout(60000);

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);

        _manager.register(server);

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
            assert(i++<10);
            Thread.sleep(10);
        }

    }


    @Test
    public void testShutdown() throws Exception
    {
        Socket client = newClient();

        client.setSoTimeout(500);

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);

        _manager.register(server);

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

        _manager.register(server);

        OutputStream clientOutputStream = client.getOutputStream();
        InputStream clientInputStream = client.getInputStream();

        int specifiedTimeout = SslConnection.LOG.isDebugEnabled()?2000:400;
        client.setSoTimeout(specifiedTimeout);

        // Write 8 and cause block waiting for 10
        _blockAt=10;
        clientOutputStream.write("12345678".getBytes("UTF-8"));
        clientOutputStream.flush();

        while(_lastEndp==null);
        
        _lastEndp.setMaxIdleTime(10*specifiedTimeout);
        Thread.sleep(2 * specifiedTimeout);
        
        // No echo as blocking for 10
        long start=System.currentTimeMillis();
        try
        {
            int b = clientInputStream.read();
            Assert.fail("Should have timed out waiting for a response, but read "+b);
        }
        catch(SocketTimeoutException e)
        {
            int elapsed = Long.valueOf(System.currentTimeMillis() - start).intValue();
            // System.err.println("blocked for " + elapsed+ "ms");
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

        _manager.register(server);

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

        _manager.register(server);
        
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

        _manager.register(server);
        int writes = 10000;

        final byte[] bytes="HelloWorld-".getBytes(StringUtil.__UTF8_CHARSET);
        byte[] count="0\n".getBytes(StringUtil.__UTF8_CHARSET);
        final CountDownLatch latch = new CountDownLatch(writes);
        final InputStream in = new BufferedInputStream(client.getInputStream());
        final long start = System.currentTimeMillis();
        client.getOutputStream().write(bytes);
        client.getOutputStream().write(count);
        client.getOutputStream().flush();

        new Thread()
        {
            public void run()
            {
                try
                {
                    while (latch.getCount()>0)
                    {
                        // Verify echo server to client
                        for (byte b0 : bytes)
                        {
                            int b = in.read();
                            assertTrue(b>0);
                            assertEquals(0xff&b0,b);
                        }
                        
                        int b=in.read();
                        while(b>0 && b!='\n')
                            b=in.read();
                        latch.countDown();
                    }
                }
                catch(Throwable e)
                {
                    System.err.println("latch="+latch.getCount());
                    System.err.println("time="+(System.currentTimeMillis()-start));
                    e.printStackTrace();
                }
            }
        }.start();


        PrintStream print = new PrintStream(client.getOutputStream());
        
        // Write client to server
        for (int i=1;i<writes;i++)
        {
            print.write(bytes);
            print.print(i);
            print.print('\n');
            if (i%100==0)
                print.flush();
            Thread.yield();
            
        }
        client.getOutputStream().flush();

        assertTrue(latch.await(100,TimeUnit.SECONDS));
    }
    

    @Test
    public void testWriteBlock() throws Exception
    {
        Socket client = newClient();

        client.setSoTimeout(10000);

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);

        _manager.register(server);

        // Write client to server
        _writeCount=10000;
        String data="Now is the time for all good men to come to the aid of the party";
        client.getOutputStream().write(data.getBytes("UTF-8"));

        for (int i=0;i<_writeCount;i++)
        {
            // Verify echo server to client
            for (int j=0;j<data.length();j++)
            {
                char c=data.charAt(j);
                int b = client.getInputStream().read();
                assertTrue(b>0);
                assertEquals("test-"+i+"/"+j,c,(char)b);
            }
            if (i==0)
                _lastEndp.setMaxIdleTime(60000);
            if (i%100==0)
                TimeUnit.MILLISECONDS.sleep(10);
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
