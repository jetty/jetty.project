//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.toolchain.test.annotation.Stress;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;


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
    protected Connection newConnection(SocketChannel channel, EndPoint endpoint)
    {
        SSLEngine engine = __sslCtxFactory.newSSLEngine();
        engine.setUseClientMode(false);
        SslConnection sslConnection = new SslConnection(__byteBufferPool, _threadPool, endpoint, engine);
        sslConnection.setRenegotiationAllowed(__sslCtxFactory.isRenegotiationAllowed());
        sslConnection.setRenegotiationLimit(__sslCtxFactory.getRenegotiationLimit());
        Connection appConnection = super.newConnection(channel,sslConnection.getDecryptedEndPoint());
        sslConnection.getDecryptedEndPoint().setConnection(appConnection);
        return sslConnection;
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
