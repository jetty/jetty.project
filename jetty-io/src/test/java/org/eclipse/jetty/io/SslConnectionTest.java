package org.eclipse.jetty.io;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;

import junit.framework.Assert;

import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;


public class SslConnectionTest
{
    private static SslContextFactory __sslCtxFactory=new SslContextFactory();
    private static ByteBufferPool __byteBufferPool = new StandardByteBufferPool();

    protected volatile AsyncEndPoint _lastEndp;
    protected ServerSocketChannel _connector;
    protected QueuedThreadPool _threadPool = new QueuedThreadPool();
    protected ScheduledExecutorService _scheduler = Executors.newSingleThreadScheduledExecutor();
    protected SelectorManager _manager = new SelectorManager()
    {
        @Override
        protected void execute(Runnable task)
        {
            _threadPool.execute(task);
        }

        @Override
        public AsyncConnection newConnection(SocketChannel channel, AsyncEndPoint endpoint, Object attachment)
        {
            SSLEngine engine = __sslCtxFactory.newSslEngine();
            engine.setUseClientMode(false);
            SslConnection sslConnection = new SslConnection(__byteBufferPool, _threadPool, endpoint, engine);

            AsyncConnection appConnection = new TestConnection(sslConnection.getDecryptedEndPoint());
            sslConnection.getDecryptedEndPoint().setAsyncConnection(appConnection);
            connectionOpened(appConnection);

            return sslConnection;
        }

        @Override
        protected SelectChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey selectionKey) throws IOException
        {
            SelectChannelEndPoint endp = new SelectChannelEndPoint(channel,selectSet, selectionKey, _scheduler, 60000);
            _lastEndp=endp;
            return endp;
        }
    };

    // Must be volatile or the test may fail spuriously
    protected volatile int _blockAt=0;

    @BeforeClass
    public static void initSslEngine() throws Exception
    {
        File keystore = MavenTestingUtils.getTestResourceFile("keystore");
        __sslCtxFactory.setKeyStorePath(keystore.getAbsolutePath());
        __sslCtxFactory.setKeyStorePassword("storepwd");
        __sslCtxFactory.setKeyManagerPassword("keypwd");
        __sslCtxFactory.start();
    }

    @Before
    public void startManager() throws Exception
    {
        _lastEndp=null;
        _connector = ServerSocketChannel.open();
        _connector.socket().bind(null);
        _threadPool.start();
        _manager.start();
    }

    @After
    public void stopManager() throws Exception
    {
        if (_lastEndp.isOpen())
            _lastEndp.close();
        _manager.stop();
        _threadPool.stop();
        _connector.close();
    }

    public class TestConnection extends AbstractAsyncConnection
    {
        ByteBuffer _in = BufferUtil.allocate(8*1024);

        public TestConnection(AsyncEndPoint endp)
        {
            super(endp, _threadPool);
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            fillInterested();
        }
        
        @Override
        public void onClose()
        {
            super.onClose();
        }

        @Override
        public synchronized void onFillable()
        {
            AsyncEndPoint endp = getEndPoint();
            try
            {
                boolean progress=true;
                while(progress)
                {
                    progress=false;

                    // Fill the input buffer with everything available
                    int filled=endp.fill(_in);
                    while (filled>0)
                    {
                        progress=true;
                        filled=endp.fill(_in);
                    }

                    // Write everything
                    int l=_in.remaining();
                    if (l>0)
                    {
                        FutureCallback<Void> blockingWrite= new FutureCallback<>();
                        endp.write(null,blockingWrite,_in);
                        blockingWrite.get();
                    }

                    // are we done?
                    if (endp.isInputShutdown())
                    {
                        endp.shutdownOutput();
                    }
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
                if (endp.isOpen())
                    fillInterested();
            }
        }
    }
    protected Socket newClient() throws IOException
    {
        SSLSocket socket = __sslCtxFactory.newSslSocket();
        socket.connect(_connector.socket().getLocalSocketAddress());
        return socket;
    }

    @Test
    public void testHelloWorld() throws Exception
    {
        Socket client = newClient();
        client.setSoTimeout(60000);

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);
        _manager.accept(server);

        client.getOutputStream().write("HelloWorld".getBytes("UTF-8"));
        byte[] buffer = new byte[1024];
        int len=client.getInputStream().read(buffer);
        Assert.assertEquals(10,len);
        Assert.assertEquals("HelloWorld",new String(buffer,0,len,StringUtil.__UTF8_CHARSET));

        client.close();
    }


    @Test
    public void testManyLines() throws Exception
    {
        final Socket client = newClient();
        client.setSoTimeout(60000);

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);
        _manager.accept(server);

        final int LINES=20;
        final CountDownLatch count=new CountDownLatch(LINES);


        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(),StringUtil.__UTF8_CHARSET));
                    while(count.getCount()>0)
                    {
                        String line=in.readLine();
                        if (line==null)
                            break;
                        // System.err.println(line);
                        count.countDown();
                    }
                }
                catch(IOException e)
                {
                    e.printStackTrace();
                }
            }
        }.start();

        for (int i=0;i<LINES;i++)
        {
            client.getOutputStream().write(("HelloWorld "+i+"\n").getBytes("UTF-8"));
            // System.err.println("wrote");
            if (i%1000==0)
            {
                client.getOutputStream().flush();
                Thread.sleep(10);
            }
        }

        Assert.assertTrue(count.await(20,TimeUnit.SECONDS));
        client.close();

    }


}
