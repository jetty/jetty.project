package org.eclipse.jetty.io.nio;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.BeforeClass;
import org.junit.Test;


public class SslSelectChannelEndPointTest extends SelectChannelEndPointTest
{
    static SslContextFactory __sslCtxFactory=new SslContextFactory();
    
    @BeforeClass
    public static void initSslEngine() throws Exception
    {
        File keystore = MavenTestingUtils.getTestResourceFile("keystore");
        __sslCtxFactory.setKeyStorePath(keystore.getAbsolutePath());
        __sslCtxFactory.setKeyStorePassword("storepwd");
        __sslCtxFactory.setKeyManagerPassword("keypwd");
        __sslCtxFactory.start();
    }
    
    @Override
    protected Socket newClient() throws IOException
    {
        SSLSocket socket = __sslCtxFactory.newSslSocket();
        socket.connect(_connector.socket().getLocalSocketAddress());
        return socket;
    }

    @Override
    protected AsyncConnection newConnection(SocketChannel channel, EndPoint endpoint)
    {
        SSLEngine engine = __sslCtxFactory.newSslEngine();
        engine.setUseClientMode(false);
        SslConnection connection = new SslConnection(engine,endpoint);

        AsyncConnection delegate = super.newConnection(channel,connection.getSslEndPoint());
        connection.setConnection(delegate);
        return connection;
    }

    @Test
    @Override
    public void testEcho() throws Exception
    {
        super.testEcho();
    }

    
    @Test
    @Override
    public void testShutdown() throws Exception
    {

        SocketChannel client = SocketChannel.open(_connector.socket().getLocalSocketAddress());
        client.socket().setSoTimeout(500);
        
        SocketChannel server = _connector.accept();
        server.configureBlocking(false);
        _manager.register(server);
        
        SSLEngine engine = __sslCtxFactory.newSslEngine();
        engine.setUseClientMode(true);
        engine.beginHandshake();
        
        ByteBuffer appOut = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize());
        ByteBuffer sslOut = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
        ByteBuffer appIn = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize());
        ByteBuffer sslIn = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
        
        appOut.put("HelloWorld".getBytes("UTF-8"));
        appOut.flip();
        
        System.err.println(engine.getHandshakeStatus());
        while (engine.getHandshakeStatus()!=HandshakeStatus.NOT_HANDSHAKING)
        {
            if (engine.getHandshakeStatus()==HandshakeStatus.NEED_WRAP)
            {
                SSLEngineResult result =engine.wrap(appOut,sslOut);
                System.err.println(result);
                sslOut.flip();
                int flushed=client.write(sslOut);
                System.err.println("out="+flushed);
                sslOut.clear();
            }

            if (engine.getHandshakeStatus()==HandshakeStatus.NEED_UNWRAP)
            {
                int filled=client.read(sslIn);
                System.err.println("in="+filled);
                sslIn.flip();
                SSLEngineResult result =engine.unwrap(sslIn,appIn);
                sslIn.flip();
                sslIn.compact();
                System.err.println(result);
            }

            if (engine.getHandshakeStatus()==HandshakeStatus.NEED_TASK)
            {                    
                Runnable task;
                while ((task=engine.getDelegatedTask())!=null)
                    task.run();
                System.err.println(engine.getHandshakeStatus());
            }
        }
        

        /* 
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
        
        */
    }

}
