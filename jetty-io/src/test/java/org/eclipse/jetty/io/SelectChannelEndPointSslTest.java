//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.io;

import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.toolchain.test.IO;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.annotation.Stress;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;


public class SelectChannelEndPointSslTest extends SelectChannelEndPointTest
{
    private static SslContextFactory __sslCtxFactory=new SslContextFactory();
    private static ByteBufferPool __byteBufferPool = new MappedByteBufferPool();

    @BeforeClass
    public static void initSslEngine() throws Exception
    {
        File keystore = MavenTestingUtils.getTestResourceFile("keystore");
        __sslCtxFactory.setKeyStorePath(keystore.getAbsolutePath());
        __sslCtxFactory.setKeyStorePassword("storepwd");
        __sslCtxFactory.setKeyManagerPassword("keypwd");
        __sslCtxFactory.setEndpointIdentificationAlgorithm("");
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
    protected Connection newConnection(SelectableChannel channel, EndPoint endpoint)
    {
        SSLEngine engine = __sslCtxFactory.newSSLEngine();
        engine.setUseClientMode(false);
        SslConnection sslConnection = new SslConnection(__byteBufferPool, _threadPool, endpoint, engine);
        sslConnection.setRenegotiationAllowed(__sslCtxFactory.isRenegotiationAllowed());
        Connection appConnection = super.newConnection(channel,sslConnection.getDecryptedEndPoint());
        sslConnection.getDecryptedEndPoint().setConnection(appConnection);
        return sslConnection;
    }

    private static final byte[] content =
        ("Lorem ipsum dolor sit amet, consectetur adipiscing elit. In quis felis nunc. \r\n"+
        "Quisque suscipit mauris et ante auctor ornare rhoncus lacus aliquet. Pellentesque "+
        "habitant morbi tristique senectus et netus et malesuada fames ac turpis egestas. \r\n"+
        "Vestibulum sit amet felis augue, vel convallis dolor. Cras accumsan vehicula diam "+
        "at faucibus. Etiam in urna turpis, sed congue mi. Morbi et lorem eros. Donec vulputate "+
        "velit in risus suscipit lobortis. Aliquam id urna orci, nec sollicitudin ipsum. \r\n"+
        "Cras a orci turpis. Donec suscipit vulputate cursus. Mauris nunc tellus, fermentum "+
        "eu auctor ut, mollis at diam. Quisque porttitor ultrices metus, vitae tincidunt massa "+
        "sollicitudin a. Vivamus porttitor libero eget purus hendrerit cursus. Integer aliquam "+
        "consequat mauris quis luctus. Cras enim nibh, dignissim eu faucibus ac, mollis nec neque. "+
        "Aliquam purus mauris, consectetur nec convallis lacinia, porta sed ante. Suspendisse "+
        "et cursus magna. Donec orci enim, molestie a lobortis eu, imperdiet vitae neque.\r\n").getBytes();

    @Test
    public void testGoogle() throws Exception
    {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION,true);     
        CRC32 crc = new CRC32();

        crc.update(content,0,content.length);
        deflater.setInput(content,0,content.length);
        deflater.finish();
        
        byte[] compressed = new byte[16*1024];
        
        int length=deflater.deflate(compressed,0,compressed.length,Deflater.NO_FLUSH);
        System.err.println(deflater.finished());

        int v=(int)crc.getValue();
        compressed[length++]=(byte)(v & 0xFF);
        compressed[length++]=(byte)((v>>>8) & 0xFF);
        compressed[length++]=(byte)((v>>>16) & 0xFF);
        compressed[length++]=(byte)((v>>>24) & 0xFF);

        v=deflater.getTotalIn();
        compressed[length++]=(byte)(v & 0xFF);
        compressed[length++]=(byte)((v>>>8) & 0xFF);
        compressed[length++]=(byte)((v>>>16) & 0xFF);
        compressed[length++]=(byte)((v>>>24) & 0xFF);
        
        SslContextFactory sslCtxFactory=new SslContextFactory(true);
        sslCtxFactory.start();
        try(SSLSocket socket = sslCtxFactory.newSslSocket())
        { 
            String host = "20160922t165538-dot-jetty9-work.appspot.com"; // env:flex
            // String host = "20160922t162437-dot-jetty9-work.appspot.com";
            socket.connect(new InetSocketAddress(host,443));
            
            String request = "POST /dump/test HTTP/1.0\r\n"+
            "Host: "+host+"\r\n"+
            "Accept-Encoding: gzip\r\n"+
            "Connection: close\r\n"+
            "Random: header\r\n"+
            "Content-Encoding: gzip\r\n"+
            "Content-Type: text/plain\r\n"+
            "Content-Length: "+length+"\r\n"+
            "\r\n";
            
            socket.getOutputStream().write(request.getBytes());
            socket.getOutputStream().write(compressed,0,length);
            socket.getOutputStream().flush();
            
            IO.copy(socket.getInputStream(),System.out);
            
        }
    }


    @Test
    @Override
    public void testEcho() throws Exception
    {
        super.testEcho();
    }


    @Ignore // SSL does not do half closes
    @Override
    public void testShutdown() throws Exception
    {
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

        SSLEngine engine = __sslCtxFactory.newSSLEngine();
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
        appOut.put("HelloWorld".getBytes(StandardCharsets.UTF_8));
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

        if (debug) System.err.println("Shutting down output");
        client.socket().shutdownOutput();

        filled=client.read(sslIn);
        if (debug) System.err.println("in="+filled);
        
        if (filled>=0)
        {
            // this is the old behaviour. 
            sslIn.flip();
            try
            {
                // Since the client closed abruptly, the server is sending a close alert with a failure
                engine.unwrap(sslIn, appIn);
                Assert.fail();
            }
            catch (SSLException x)
            {
                // Expected
            }
        }

        sslIn.clear();
        filled=client.read(sslIn);
        Assert.assertEquals(-1,filled);

        Thread.sleep(100); // TODO This should not be needed
        Assert.assertFalse(server.isOpen());
    }

    @Test
    @Override
    public void testWriteBlocked() throws Exception
    {
        super.testWriteBlocked();
    }

    @Override
    public void testReadBlocked() throws Exception
    {
        super.testReadBlocked();
    }

    @Override
    public void testIdle() throws Exception
    {
        super.testIdle();
    }

    @Test
    @Override
    @Stress("Requires a relatively idle (network wise) environment")
    public void testStress() throws Exception
    {
        super.testStress();
    }

    @Test
    public void checkSslEngineBehaviour() throws Exception
    {
        SSLEngine server = __sslCtxFactory.newSSLEngine();
        SSLEngine client = __sslCtxFactory.newSSLEngine();

        ByteBuffer netC2S = ByteBuffer.allocate(server.getSession().getPacketBufferSize());
        ByteBuffer netS2C = ByteBuffer.allocate(server.getSession().getPacketBufferSize());
        ByteBuffer serverIn = ByteBuffer.allocate(server.getSession().getApplicationBufferSize());
        ByteBuffer serverOut = ByteBuffer.allocate(server.getSession().getApplicationBufferSize());
        ByteBuffer clientIn = ByteBuffer.allocate(client.getSession().getApplicationBufferSize());

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
