package org.eclipse.jetty.io.nio;

import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

import org.eclipse.jetty.io.EndPointTest;
import org.junit.AfterClass;
import org.junit.BeforeClass;

public class ChannelEndPointTest extends EndPointTest<ChannelEndPoint>
{
    static ServerSocketChannel connector;
    
    @BeforeClass
    public static void open() throws Exception
    {
        connector = ServerSocketChannel.open();
        connector.socket().bind(null);
    }

    @AfterClass
    public static void close() throws Exception
    {
        connector.close();
        connector=null;
    }

    @Override
    protected EndPointPair<ChannelEndPoint> newConnection() throws Exception
    {
        EndPointPair<ChannelEndPoint> c = new EndPointPair<ChannelEndPoint>();
        
        c.client=new ChannelEndPoint(SocketChannel.open(connector.socket().getLocalSocketAddress()));
        c.server=new ChannelEndPoint(connector.accept());
        return c;
    }

    @Override
    public void testClientServerExchange() throws Exception
    {
        super.testClientServerExchange();
    }
}
