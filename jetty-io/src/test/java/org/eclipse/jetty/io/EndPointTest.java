package org.eclipse.jetty.io;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.rmi.server.ServerCloneException;
import java.util.concurrent.Exchanger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ServerSocketFactory;

import org.eclipse.jetty.io.bio.SocketEndPoint;
import org.eclipse.jetty.io.nio.IndirectNIOBuffer;
import org.eclipse.jetty.io.nio.SelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectorManager;
import org.eclipse.jetty.io.nio.SslSelectChannelEndPoint;
import org.eclipse.jetty.io.nio.SelectorManager.SelectSet;
import org.junit.Assert;
import org.junit.Test;

public class EndPointTest
{
    @Test
    public void testSocketEndPoints() throws Exception
    {
        final ServerSocket server = new ServerSocket();
        server.bind(null);
        
        final Exchanger<Socket> accepted = new Exchanger<Socket>();
        new Thread(){
            public void run()
            {
                try 
                {
                    accepted.exchange(server.accept());
                }
                catch(Exception e)
                {
                    e.printStackTrace();
                }
            }
        }.start();
        
        Socket s0 = new Socket(server.getInetAddress(),server.getLocalPort());
        Socket s1 = accepted.exchange(null,5,TimeUnit.SECONDS);
        
        SocketEndPoint in = new SocketEndPoint(s0);
        SocketEndPoint out = new SocketEndPoint(s1);
        
        check(in,out);
    }
    
    
    private void check(EndPoint in, EndPoint out) throws Exception
    {
        String data="Now is the time for all good men to come to the aid of the party";
        Buffer send = new ByteArrayBuffer(data);
        Buffer receive = new IndirectNIOBuffer(4096);
        
        int lo=out.flush(send);
        int li=in.fill(receive);
        
        Assert.assertEquals(data.length(),lo);
        Assert.assertEquals(data.length(),li);
        Assert.assertEquals(data,receive.toString());
        
        in.close();
        out.close();
    }
}
