//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class SslEngineBehaviorTest
{
    private static SslContextFactory sslCtxFactory;

    @BeforeAll
    public static void startSsl() throws Exception
    {
        sslCtxFactory = new SslContextFactory.Server();
        File keystore = MavenTestingUtils.getTestResourceFile("keystore");
        sslCtxFactory.setKeyStorePath(keystore.getAbsolutePath());
        sslCtxFactory.setKeyStorePassword("storepwd");
        sslCtxFactory.setKeyManagerPassword("keypwd");
        sslCtxFactory.start();
    }

    @AfterAll
    public static void stopSsl() throws Exception
    {
        sslCtxFactory.stop();
    }

    @Test
    @EnabledOnJre(JRE.JAVA_8)
    public void checkSslEngineBehaviour() throws Exception
    {
        SSLEngine server = sslCtxFactory.newSSLEngine();
        SSLEngine client = sslCtxFactory.newSSLEngine();

        ByteBuffer netC2S = ByteBuffer.allocate(server.getSession().getPacketBufferSize());
        ByteBuffer netS2C = ByteBuffer.allocate(server.getSession().getPacketBufferSize());
        ByteBuffer serverIn = ByteBuffer.allocate(server.getSession().getApplicationBufferSize());
        ByteBuffer serverOut = ByteBuffer.allocate(server.getSession().getApplicationBufferSize());
        ByteBuffer clientIn = ByteBuffer.allocate(client.getSession().getApplicationBufferSize());

        SSLEngineResult result;

        // start the client
        client.setUseClientMode(true);
        client.beginHandshake();
        assertEquals(SSLEngineResult.HandshakeStatus.NEED_WRAP, client.getHandshakeStatus());

        // what if we try an unwrap?
        netS2C.flip();
        result = client.unwrap(netS2C, clientIn);
        // unwrap is a noop
        assertEquals(SSLEngineResult.Status.OK, result.getStatus());
        assertEquals(0, result.bytesConsumed());
        assertEquals(0, result.bytesProduced());
        assertEquals(SSLEngineResult.HandshakeStatus.NEED_WRAP, result.getHandshakeStatus());
        netS2C.clear();

        // do the needed WRAP of empty buffer
        result = client.wrap(BufferUtil.EMPTY_BUFFER, netC2S);
        // unwrap is a noop
        assertEquals(SSLEngineResult.Status.OK, result.getStatus());
        assertEquals(0, result.bytesConsumed());
        assertThat(result.bytesProduced(), greaterThan(0));
        assertEquals(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, result.getHandshakeStatus());
        netC2S.flip();
        assertEquals(netC2S.remaining(), result.bytesProduced());

        // start the server
        server.setUseClientMode(false);
        server.beginHandshake();
        assertEquals(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, server.getHandshakeStatus());

        // what if we try a needless wrap?
        serverOut.put(BufferUtil.toBuffer("Hello World"));
        serverOut.flip();
        result = server.wrap(serverOut, netS2C);
        // wrap is a noop
        assertEquals(SSLEngineResult.Status.OK, result.getStatus());
        assertEquals(0, result.bytesConsumed());
        assertEquals(0, result.bytesProduced());
        assertEquals(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, result.getHandshakeStatus());

        // Do the needed unwrap, to an empty buffer
        result = server.unwrap(netC2S, BufferUtil.EMPTY_BUFFER);
        assertEquals(SSLEngineResult.Status.BUFFER_OVERFLOW, result.getStatus());
        assertEquals(0, result.bytesConsumed());
        assertEquals(0, result.bytesProduced());
        assertEquals(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, result.getHandshakeStatus());

        // Do the needed unwrap, to a full buffer
        serverIn.position(serverIn.limit());
        result = server.unwrap(netC2S, serverIn);
        assertEquals(SSLEngineResult.Status.BUFFER_OVERFLOW, result.getStatus());
        assertEquals(0, result.bytesConsumed());
        assertEquals(0, result.bytesProduced());
        assertEquals(SSLEngineResult.HandshakeStatus.NEED_UNWRAP, result.getHandshakeStatus());

        // Do the needed unwrap, to an empty buffer
        serverIn.clear();
        result = server.unwrap(netC2S, serverIn);
        assertEquals(SSLEngineResult.Status.OK, result.getStatus());
        assertThat(result.bytesConsumed(), greaterThan(0));
        assertEquals(0, result.bytesProduced());
        assertEquals(SSLEngineResult.HandshakeStatus.NEED_TASK, result.getHandshakeStatus());

        server.getDelegatedTask().run();

        assertEquals(SSLEngineResult.HandshakeStatus.NEED_WRAP, server.getHandshakeStatus());
    }
}
