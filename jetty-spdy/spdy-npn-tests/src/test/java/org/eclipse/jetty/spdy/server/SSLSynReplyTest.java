//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.spdy.server;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.List;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class SSLSynReplyTest extends AbstractNPNTest
{
    @Test
    public void testGentleCloseDuringHandshake() throws Exception
    {
        InetSocketAddress address = prepare();
        SslContextFactory sslContextFactory = newSslContextFactory();
        sslContextFactory.start();
        SSLEngine sslEngine = sslContextFactory.newSSLEngine(address);
        sslEngine.setUseClientMode(true);
        NextProtoNego.put(sslEngine, new NextProtoNego.ClientProvider()
        {
            @Override
            public boolean supports()
            {
                return true;
            }

            @Override
            public void unsupported()
            {
            }

            @Override
            public String selectProtocol(List<String> protocols)
            {
                return null;
            }
        });
        sslEngine.beginHandshake();

        ByteBuffer encrypted = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());
        sslEngine.wrap(BufferUtil.EMPTY_BUFFER, encrypted);
        encrypted.flip();

        try (SocketChannel channel = SocketChannel.open(address))
        {
            // Send ClientHello, immediately followed by TLS Close Alert and then by FIN
            channel.write(encrypted);
            sslEngine.closeOutbound();
            encrypted.clear();
            sslEngine.wrap(BufferUtil.EMPTY_BUFFER, encrypted);
            encrypted.flip();
            channel.write(encrypted);
            channel.shutdownOutput();

            // Read ServerHello from server
            encrypted.clear();
            int read = channel.read(encrypted);
            encrypted.flip();
            Assert.assertTrue(read > 0);
            // Cannot decrypt, as the SSLEngine has been already closed

            // Now if we read more, we should either read the TLS Close Alert, or directly -1
            encrypted.clear();
            read = channel.read(encrypted);
            // Sending a TLS Close Alert during handshake results in an exception when
            // unwrapping that the server react to by closing the connection abruptly.
            Assert.assertTrue(read < 0);
        }
    }

    @Test
    public void testAbruptCloseDuringHandshake() throws Exception
    {
        InetSocketAddress address = prepare();
        SslContextFactory sslContextFactory = newSslContextFactory();
        sslContextFactory.start();
        SSLEngine sslEngine = sslContextFactory.newSSLEngine(address);
        sslEngine.setUseClientMode(true);
        NextProtoNego.put(sslEngine, new NextProtoNego.ClientProvider()
        {
            @Override
            public boolean supports()
            {
                return true;
            }

            @Override
            public void unsupported()
            {
            }

            @Override
            public String selectProtocol(List<String> protocols)
            {
                return null;
            }
        });
        sslEngine.beginHandshake();

        ByteBuffer encrypted = ByteBuffer.allocate(sslEngine.getSession().getPacketBufferSize());
        sslEngine.wrap(BufferUtil.EMPTY_BUFFER, encrypted);
        encrypted.flip();

        try (SocketChannel channel = SocketChannel.open(address))
        {
            // Send ClientHello, immediately followed by FIN (no TLS Close Alert)
            channel.write(encrypted);
            channel.shutdownOutput();

            // Read ServerHello from server
            encrypted.clear();
            int read = channel.read(encrypted);
            encrypted.flip();
            Assert.assertTrue(read > 0);
            ByteBuffer decrypted = ByteBuffer.allocate(sslEngine.getSession().getApplicationBufferSize());
            sslEngine.unwrap(encrypted, decrypted);

            // Now if we read more, we should either read the TLS Close Alert, or directly -1
            encrypted.clear();
            read = channel.read(encrypted);
            // Since we have close the connection abruptly, the server also does so
            Assert.assertTrue(read < 0);
        }
    }
}
