package org.eclipse.jetty.io.bio;

import java.net.ServerSocket;
import java.net.Socket;

import org.eclipse.jetty.io.EndPointTest;
import org.junit.AfterClass;
import org.junit.BeforeClass;

public class SocketEndPointTest extends EndPointTest<SocketEndPoint>
{
    static ServerSocket connector;
    
    @BeforeClass
    public static void open() throws Exception
    {
        connector = new ServerSocket();
        connector.bind(null);
    }

    @AfterClass
    public static void close() throws Exception
    {
        connector.close();
        connector=null;
    }

    @Override
    protected Connection<SocketEndPoint> newConnection() throws Exception
    {
        Connection<SocketEndPoint> c = new Connection<SocketEndPoint>();
        c.client=new SocketEndPoint(new Socket(connector.getInetAddress(),connector.getLocalPort()));
        c.server=new SocketEndPoint(connector.accept());
        return c;
    }
    

}
