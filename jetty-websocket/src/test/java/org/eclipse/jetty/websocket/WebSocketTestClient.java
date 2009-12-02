package org.eclipse.jetty.websocket;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;

import org.eclipse.jetty.io.ByteArrayBuffer;
import org.eclipse.jetty.io.ConnectedEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectorManager;
import org.eclipse.jetty.io.nio.SelectorManager.SelectSet;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;

public class WebSocketTestClient extends AbstractLifeCycle
{
    final ThreadPool _threadpool = new QueuedThreadPool();
    final WebSocketBuffers _buffers = new WebSocketBuffers(4*4096);
    
    SelectorManager _manager = new SelectorManager()
    {
        @Override
        protected SocketChannel acceptChannel(SelectionKey key) throws IOException
        {
            throw new IllegalStateException();
        }

        @Override
        public boolean dispatch(Runnable task)
        {
            return _threadpool.dispatch(task);
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
        protected void endPointUpgraded(ConnectedEndPoint endpoint, Connection oldConnection)
        {
        }

        @Override
        protected Connection newConnection(SocketChannel channel, SelectChannelEndPoint endpoint)
        {
            WebSocket ws=(WebSocket)endpoint.getSelectionKey().attachment();
            WebSocketConnection connection = new WebSocketConnection(ws,endpoint,_buffers,System.currentTimeMillis(), 30000);
            
            // TODO Blocking upgrade code.  Should be  async
            ByteArrayBuffer upgrade=new ByteArrayBuffer(
                "GET / HTTP/1.1\r\n"+
                "Host: localhost:8080\r\n" +
                "Upgrade: WebSocket\r\n" +
                "Connection: Upgrade\r\n" +
                "\r\n");
            try
            {
                while (upgrade.length()>0 && endpoint.isOpen())
                {
                    int l = endpoint.flush(upgrade);
                    if (l>0)
                        upgrade.skip(l);
                    Thread.sleep(10);
                }
                IndirectNIOBuffer upgraded = new IndirectNIOBuffer(2048);
                String up;
                do
                {
                    endpoint.fill(upgraded);
                    up=upgraded.toString();
                }
                while(endpoint.isOpen() && !up.contains("\r\n\r\n"));
            }
            catch(Exception e)
            {
                Log.warn(e);
            }
            
            ws.onConnect(connection);
            
            return connection;
        }

        @Override
        protected SelectChannelEndPoint newEndPoint(SocketChannel channel, SelectSet selectSet, SelectionKey key) throws IOException
        {
            SelectChannelEndPoint ep=new SelectChannelEndPoint(channel,selectSet,key);
            return ep;
        }
        
    };

    protected void doStart()
        throws Exception
    {
        ((LifeCycle)_threadpool).start();
        _manager.start();
        
        _threadpool.dispatch(new Runnable()
        {

            public void run()
            {
                while (isRunning())
                {
                    try
                    {
                        _manager.doSelect(0);
                    }
                    catch (Exception e)
                    {
                        Log.warn(e.toString());
                        Log.debug(e);
                        Thread.yield();
                    }
                }
            }
        });
        
        
    }
    
    public void open(SocketAddress addr, WebSocket websocket)
        throws IOException
    {
        SocketChannel channel = SocketChannel.open();
        channel.configureBlocking( false );
        channel.connect(addr);
        
        _manager.register( channel, websocket);
    }

    public static void main(String[] args)
        throws Exception
    {
        WebSocketTestClient client = new WebSocketTestClient();
        client.start();
        
        TestWebSocket[] ws=new TestWebSocket[100];
        
        for (int i=0;i<ws.length;i++)
            ws[i]=new TestWebSocket(i);
        
        for (TestWebSocket w : ws)
            client.open(new InetSocketAddress("localhost",8080),w);
        
        Thread.sleep(5000);

        for (TestWebSocket w : ws)
            if (w._out!=null)
                w._out.sendMessage(WebSocket.SENTINEL_FRAME,"hello world from "+w._id);
        
        
        
        client._threadpool.join();
    }
    
    static class TestWebSocket implements WebSocket
    {
        int _id;
        Outbound _out;
        
        TestWebSocket(int i)
        {
            _id=i;
        }
        
        public void onConnect(Outbound outbound)
        {
            _out=outbound;
            System.err.println("onConnect");

        }

        public void onDisconnect()
        {
            System.err.println("onDisconnect");

        }

        public void onMessage(byte frame, String data)
        {
            System.err.println("onMessage "+data);
        }

        public void onMessage(byte frame, byte[] data, int offset, int length)
        {
        }
    }
    
}
