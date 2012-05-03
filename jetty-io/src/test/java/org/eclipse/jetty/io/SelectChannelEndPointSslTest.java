package org.eclipse.jetty.io;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.io.SslConnection;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;


public class SelectChannelEndPointSslTest extends SelectChannelEndPointTest
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
    protected AbstractAsyncConnection newConnection(SocketChannel channel, AsyncEndPoint endpoint)
    {
        SSLEngine engine = __sslCtxFactory.newSslEngine();
        engine.setUseClientMode(false);
        SslConnection connection = new SslConnection(engine,endpoint);

        AbstractAsyncConnection delegate = super.newConnection(channel,connection.getAppEndPoint());
        connection.setAppConnection(delegate);
        return connection;
    }

    @Test
    @Override
    public void testEcho() throws Exception
    {
        super.testEcho();
    }


    @Ignore
    @Override
    public void testShutdown() throws Exception
    {
        // SSL does not do half closes
    }

    
    @Test
    public void testTcpClose() throws Exception
    {
        // This test replaces SSLSocket() with a very manual SSL client
        // so we can close TCP underneath SSL.

        SocketChannel client = SocketChannel.open(_connector.socket().getLocalSocketAddress());
        client.socket().setSoTimeout(500);

        SocketChannel server = _connector.accept();
        server.configureBlocking(false);
        _manager.register(server);

        SSLEngine engine = __sslCtxFactory.newSslEngine();
        engine.setUseClientMode(true);
        engine.beginHandshake();

        ByteBuffer appOut = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize());
        ByteBuffer sslOut = ByteBuffer.allocate(engine.getSession().getPacketBufferSize()*2);
        ByteBuffer appIn = ByteBuffer.allocate(engine.getSession().getApplicationBufferSize());
        ByteBuffer sslIn = ByteBuffer.allocate(engine.getSession().getPacketBufferSize()*2);

        boolean debug=false;

        if (debug) System.err.println(engine.getHandshakeStatus());
        int loop=20;
        while (engine.getHandshakeStatus()!=HandshakeStatus.NOT_HANDSHAKING)
        {
            if (--loop==0)
                throw new IllegalStateException();

            if (engine.getHandshakeStatus()==HandshakeStatus.NEED_WRAP)
            {
                if (debug) System.err.printf("sslOut %d-%d-%d%n",sslOut.position(),sslOut.limit(),sslOut.capacity());
                if (debug) System.err.printf("appOut %d-%d-%d%n",appOut.position(),appOut.limit(),appOut.capacity());
                SSLEngineResult result =engine.wrap(appOut,sslOut);
                if (debug) System.err.println(result);
                sslOut.flip();
                int flushed=client.write(sslOut);
                if (debug) System.err.println("out="+flushed);
                sslOut.clear();
            }

            if (engine.getHandshakeStatus()==HandshakeStatus.NEED_UNWRAP)
            {
                if (debug) System.err.printf("sslIn %d-%d-%d%n",sslIn.position(),sslIn.limit(),sslIn.capacity());
                if (sslIn.position()==0)
                {
                    int filled=client.read(sslIn);
                    if (debug) System.err.println("in="+filled);
                }
                sslIn.flip();
                if (debug) System.err.printf("sslIn %d-%d-%d%n",sslIn.position(),sslIn.limit(),sslIn.capacity());
                SSLEngineResult result =engine.unwrap(sslIn,appIn);
                if (debug) System.err.println(result);
                if (debug) System.err.printf("sslIn %d-%d-%d%n",sslIn.position(),sslIn.limit(),sslIn.capacity());
                if (sslIn.hasRemaining())
                    sslIn.compact();
                else
                    sslIn.clear();
                if (debug) System.err.printf("sslIn %d-%d-%d%n",sslIn.position(),sslIn.limit(),sslIn.capacity());
            }

            if (engine.getHandshakeStatus()==HandshakeStatus.NEED_TASK)
            {
                Runnable task;
                while ((task=engine.getDelegatedTask())!=null)
                    task.run();
                if (debug) System.err.println(engine.getHandshakeStatus());
            }
        }

        if (debug) System.err.println("\nSay Hello");

        // write a message
        appOut.put("HelloWorld".getBytes("UTF-8"));
        appOut.flip();
        SSLEngineResult result =engine.wrap(appOut,sslOut);
        if (debug) System.err.println(result);
        sslOut.flip();
        int flushed=client.write(sslOut);
        if (debug) System.err.println("out="+flushed);
        sslOut.clear();
        appOut.clear();

        // read the response
        int filled=client.read(sslIn);
        if (debug) System.err.println("in="+filled);
        sslIn.flip();
        result =engine.unwrap(sslIn,appIn);
        if (debug) System.err.println(result);
        if (sslIn.hasRemaining())
            sslIn.compact();
        else
            sslIn.clear();

        appIn.flip();
        String reply= new String(appIn.array(),appIn.arrayOffset(),appIn.remaining());
        appIn.clear();

        Assert.assertEquals("HelloWorld",reply);

        SslConnection.LOG.info("javax.net.ssl.SSLException: Inbound closed before... Expected as next line!");
        if (debug) System.err.println("\nSudden Death");
        client.socket().shutdownOutput();

        filled=client.read(sslIn);
        Assert.assertEquals(-1,filled);
    }

    @Test
    @Override
    public void testWriteBlock() throws Exception
    {
        super.testWriteBlock();
    }
    
    @Test
    @Override
    public void testStress() throws Exception
    {
        // super.testStress();
    }
}
