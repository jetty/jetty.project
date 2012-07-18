package org.eclipse.jetty.io;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;


public class SslConnectionTest
{
    private static SslContextFactory __sslCtxFactory=new SslContextFactory();
    private static ByteBufferPool __byteBufferPool = new StandardByteBufferPool();

    protected volatile AsyncEndPoint _lastEndp;
    protected ServerSocketChannel _connector;
    protected QueuedThreadPool _threadPool = new QueuedThreadPool();
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

            AsyncConnection appConnection = new TestConnection(sslConnection.getSslEndPoint());
            sslConnection.getSslEndPoint().setAsyncConnection(appConnection);

            // System.err.println("New Connection "+sslConnection);
            return sslConnection;

        }

        @Override
        protected SelectChannelEndPoint newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey key) throws IOException
        {
            SelectChannelEndPoint endp = new SelectChannelEndPoint(channel,selectSet,key,getMaxIdleTime());
            endp.setAsyncConnection(selectSet.getManager().newConnection(channel,endp, key.attachment()));
            _lastEndp=endp;
            // System.err.println("newEndPoint "+endp);
            return endp;
        }
    };
    {
        _manager.setMaxIdleTime(600000); // TODO: use smaller value
    }

    // Must be volatile or the test may fail spuriously
    protected volatile int _blockAt=0;
    private volatile int _writeCount=1;


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
            super(endp,_threadPool);
        }

        @Override
        public void onOpen()
        {
            // System.err.println("onOpen");
            fillInterested();
        }

        @Override
        public void onClose()
        {
            // System.err.println("onClose");
        }

        @Override
        public synchronized void onFillable()
        {
            AsyncEndPoint endp = getEndPoint();
            // System.err.println("onReadable "+endp);
            try
            {
                boolean progress=true;
                while(progress)
                {
                    progress=false;

                    // Fill the input buffer with everything available
                    int filled=endp.fill(_in);
                    // System.err.println("filled="+filled);
                    while (filled>0)
                    {
                        progress=true;
                        filled=endp.fill(_in);
                        // System.err.println("filled="+filled);
                    }

                    // System.err.println(BufferUtil.toDetailString(_in));

                    // Write everything
                    int l=_in.remaining();
                    if (l>0)
                    {
                        FutureCallback<Void> blockingWrite= new FutureCallback<>();
                        endp.write(null,blockingWrite,_in);
                        blockingWrite.get();
                        // System.err.println("wrote "+l);
                    }

                    // are we done?
                    if (endp.isInputShutdown())
                    {
                        // System.err.println("shutdown");
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
        //Log.getRootLogger().setDebugEnabled(true);

        // Log.getRootLogger().setDebugEnabled(true);
        Socket client = newClient();
        // System.err.println("client="+client);
        client.setSoTimeout(600000); // TODO: restore to smaller value

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);
        _manager.accept(server);

        client.getOutputStream().write("HelloWorld".getBytes("UTF-8"));
        // System.err.println("wrote");
        byte[] buffer = new byte[1024];
        int len = client.getInputStream().read(buffer);
        // System.err.println(new String(buffer,0,len,"UTF-8"));

        client.close();

    }


    @Test
    @Ignore
    public void testNasty() throws Exception
    {
        //Log.getRootLogger().setDebugEnabled(true);

        // Log.getRootLogger().setDebugEnabled(true);
        final Socket client = newClient();
        // System.err.println("client="+client);
        client.setSoTimeout(600000); // TODO: restore to smaller value

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);
        _manager.accept(server);

        new Thread()
        {
            public void run()
            {
                try
                {
                    while(true)
                    {
                        byte[] buffer = new byte[1024];
                        int len = client.getInputStream().read(buffer);
                        if (len<0)
                        {
                            System.err.println("===");
                            return;
                        }
                        // System.err.println(new String(buffer,0,len,"UTF-8"));

                    }
                }
                catch(IOException e)
                {
                    e.printStackTrace();
                }
            }
        }.start();

        for (int i=0;i<100000;i++)
        {
            client.getOutputStream().write(("HelloWorld "+i+"\n").getBytes("UTF-8"));
            // System.err.println("wrote");
            if (i%1000==0)
                Thread.sleep(10);
        }

        Thread.sleep(20000);
        client.close();

    }


}
