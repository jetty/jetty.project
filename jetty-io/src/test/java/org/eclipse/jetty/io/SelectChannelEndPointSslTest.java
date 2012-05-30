package org.eclipse.jetty.io;

import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;


public class SelectChannelEndPointSslTest extends SelectChannelEndPointTest
{
    private static SslContextFactory __sslCtxFactory=new SslContextFactory();
    private static ByteBufferPool __byteBufferPool = new StandardByteBufferPool();

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
    protected AsyncConnection newConnection(SocketChannel channel, AsyncEndPoint endpoint)
    {
        SSLEngine engine = __sslCtxFactory.newSslEngine();
        engine.setUseClientMode(false);
        SslConnection sslConnection = new SslConnection(__byteBufferPool, _threadPool, endpoint, engine);

        AsyncConnection appConnection = super.newConnection(channel,sslConnection.getAppEndPoint());
        sslConnection.getAppEndPoint().setAsyncConnection(appConnection);
        return sslConnection;
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
        _manager.accept(server);

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
        super.testStress();
    }
    
    @Test
    public void checkSslEngineBehaviour() throws Exception
    {
        SSLEngine server = __sslCtxFactory.newSslEngine();
        SSLEngine client = __sslCtxFactory.newSslEngine();

        ByteBuffer netC2S = ByteBuffer.allocate(server.getSession().getPacketBufferSize());
        ByteBuffer netS2C = ByteBuffer.allocate(server.getSession().getPacketBufferSize());
        ByteBuffer serverIn = ByteBuffer.allocate(server.getSession().getApplicationBufferSize());
        ByteBuffer serverOut = ByteBuffer.allocate(server.getSession().getApplicationBufferSize());
        ByteBuffer clientIn = ByteBuffer.allocate(client.getSession().getApplicationBufferSize());
        ByteBuffer clientOut = ByteBuffer.allocate(client.getSession().getApplicationBufferSize());
        
        SSLEngineResult result;

        // start the client
        client.setUseClientMode(true);
        client.beginHandshake();
        Assert.assertEquals(HandshakeStatus.NEED_WRAP,client.getHandshakeStatus());
        
        // what if we try an unwrap?
        netS2C.flip();
        result=client.unwrap(netS2C,clientIn);
        // unwrap is a noop
        assertEquals(SSLEngineResult.Status.OK,result.getStatus());
        assertEquals(0,result.bytesConsumed());
        assertEquals(0,result.bytesProduced());
        assertEquals(HandshakeStatus.NEED_WRAP,result.getHandshakeStatus());
        netS2C.clear();
        
        // do the needed WRAP of empty buffer
        result=client.wrap(BufferUtil.EMPTY_BUFFER,netC2S);
        // unwrap is a noop
        assertEquals(SSLEngineResult.Status.OK,result.getStatus());
        assertEquals(0,result.bytesConsumed());
        assertThat(result.bytesProduced(),greaterThan(0));
        assertEquals(HandshakeStatus.NEED_UNWRAP,result.getHandshakeStatus());
        netC2S.flip();
        assertEquals(netC2S.remaining(),result.bytesProduced());
        
        
        // start the server
        server.setUseClientMode(false);
        server.beginHandshake();
        Assert.assertEquals(HandshakeStatus.NEED_UNWRAP,server.getHandshakeStatus());
        

        // what if we try a needless wrap?
        serverOut.put(BufferUtil.toBuffer("Hello World"));
        serverOut.flip();
        result=server.wrap(serverOut,netS2C);
        // wrap is a noop
        assertEquals(SSLEngineResult.Status.OK,result.getStatus());
        assertEquals(0,result.bytesConsumed());
        assertEquals(0,result.bytesProduced());
        assertEquals(HandshakeStatus.NEED_UNWRAP,result.getHandshakeStatus());
        
        
        // Do the needed unwrap, to an empty buffer
        result=server.unwrap(netC2S,BufferUtil.EMPTY_BUFFER);
        assertEquals(SSLEngineResult.Status.BUFFER_OVERFLOW,result.getStatus());
        assertEquals(0,result.bytesConsumed());
        assertEquals(0,result.bytesProduced());
        assertEquals(HandshakeStatus.NEED_UNWRAP,result.getHandshakeStatus());
        

        // Do the needed unwrap, to a full buffer
        serverIn.position(serverIn.limit());
        result=server.unwrap(netC2S,serverIn);
        assertEquals(SSLEngineResult.Status.BUFFER_OVERFLOW,result.getStatus());
        assertEquals(0,result.bytesConsumed());
        assertEquals(0,result.bytesProduced());
        assertEquals(HandshakeStatus.NEED_UNWRAP,result.getHandshakeStatus());
        

        // Do the needed unwrap, to an empty buffer
        serverIn.clear();
        result=server.unwrap(netC2S,serverIn);
        assertEquals(SSLEngineResult.Status.OK,result.getStatus());
        assertThat(result.bytesConsumed(),greaterThan(0));
        assertEquals(0,result.bytesProduced());
        assertEquals(HandshakeStatus.NEED_TASK,result.getHandshakeStatus());
        
        server.getDelegatedTask().run();

        assertEquals(HandshakeStatus.NEED_WRAP,server.getHandshakeStatus());
        
        
        

    }
}
