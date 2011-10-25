package org.eclipse.jetty.io.nio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class SelectChannelEndPointTest
{
    protected ServerSocketChannel _connector;
    protected ServerSocketChannel __serverSocket;
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
        protected void endPointUpgraded(ConnectedEndPoint endpoint, org.eclipse.jetty.io.Connection oldConnection)
        {            
        }

        @Override
        protected AsyncConnection newConnection(SocketChannel channel, SelectChannelEndPoint endpoint)
        {
            return SelectChannelEndPointTest.this.newConnection(channel,endpoint);
        }

        @Override
        protected SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectSet selectSet, SelectionKey sKey) throws IOException
        {
            return new SelectChannelEndPoint(channel,selectSet,sKey,2000);
        }    
    };
    
    @Before
    public void startManager() throws Exception
    {
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
    
    protected AsyncConnection newConnection(SocketChannel channel, SelectChannelEndPoint endpoint)
    {
        return new TestConnection(endpoint);
    }
    
    public static class TestConnection extends AbstractConnection implements AsyncConnection
    {
        NIOBuffer _in = new IndirectNIOBuffer(32*1024);
        NIOBuffer _out = new IndirectNIOBuffer(32*1024);
        boolean _echo=true;
        
        public TestConnection(EndPoint endp)
        {
            super(endp);
        }

        public org.eclipse.jetty.io.Connection handle() throws IOException
        {
            boolean progress=true;
            while(progress)
            {
                progress=false;
                _in.compact();
                if (_in.space()>0 && _endp.fill(_in)>0)
                    progress=true;

                if (_echo && _in.hasContent() && _in.skip(_out.put(_in))>0)
                    progress=true;
                
                if (_out.hasContent() && _endp.flush(_out)>0)
                    progress=true;
                
                _out.compact();
                
                if (!_out.hasContent() && _endp.isInputShutdown())
                    _endp.shutdownOutput();
            }
            return this;
        }

        public boolean isIdle()
        {
            return false;
        }

        public boolean isSuspended()
        {
            return false;
        }

        public void onClose()
        {
        }

        public void onInputShutdown() throws IOException
        {
        }
        
    }
    
    @Test
    public void testEcho() throws Exception
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
        client.getOutputStream().write("Goodbye".getBytes("UTF-8"));
        client.shutdownOutput();
        

        // Verify echo server to client
        for (char c : "Goodbye".toCharArray())
        {
            int b = client.getInputStream().read();
            assertTrue(b>0);
            assertEquals(c,(char)b);
        }
        
        // Read close
        assertEquals(-1,client.getInputStream().read());
        
    }
}
