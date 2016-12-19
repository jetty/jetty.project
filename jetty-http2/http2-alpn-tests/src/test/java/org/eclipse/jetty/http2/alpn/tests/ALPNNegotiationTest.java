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

package org.eclipse.jetty.http2.alpn.tests;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.alpn.ALPN;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class ALPNNegotiationTest extends AbstractALPNTest
{
    @Test
    public void testGentleCloseDuringHandshake() throws Exception
    {
        InetSocketAddress address = prepare();
        SslContextFactory sslContextFactory = newSslContextFactory();
        sslContextFactory.start();
        SSLEngine sslEngine = sslContextFactory.newSSLEngine(address);
        sslEngine.setUseClientMode(true);
        ALPN.put(sslEngine, new ALPN.ClientProvider()
        {
            @Override
            public void unsupported()
            {
            }

            @Override
            public List<String> protocols()
            {
                return Arrays.asList("h2");
            }

            @Override
            public void selected(String protocol)
            {
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

            // Now if we read more, we should read a TLS Alert.
            encrypted.clear();
            read = channel.read(encrypted);
            Assert.assertTrue(read > 0);
            Assert.assertEquals(21, encrypted.get(0));
            encrypted.clear();
            Assert.assertEquals(-1, channel.read(encrypted));
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
        ALPN.put(sslEngine, new ALPN.ClientProvider()
        {
            @Override
            public void unsupported()
            {
            }

            @Override
            public List<String> protocols()
            {
                return Arrays.asList("h2");
            }

            @Override
            public void selected(String s)
            {
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

            // Now if we read more, we should read the TLS Close Alert.
            encrypted.clear();
            read = channel.read(encrypted);
            encrypted.flip();
            Assert.assertTrue(read > 0);
            Assert.assertEquals(21, encrypted.get(0));
        }
    }

    @Test
    public void testClientAdvertisingHTTPServerSpeaksHTTP() throws Exception
    {
        InetSocketAddress address = prepare();

        SslContextFactory sslContextFactory = newSslContextFactory();
        sslContextFactory.start();
        SSLContext sslContext = sslContextFactory.getSslContext();

        try (SSLSocket client = (SSLSocket)sslContext.getSocketFactory().createSocket(address.getAddress(), address.getPort()))
        {
            client.setUseClientMode(true);
            client.setSoTimeout(5000);

            ALPN.put(client, new ALPN.ClientProvider()
            {
                @Override
                public void unsupported()
                {
                }

                @Override
                public List<String> protocols()
                {
                    return Arrays.asList("http/1.1");
                }

                @Override
                public void selected(String protocol)
                {
                    Assert.assertEquals("http/1.1", protocol);
                }
            });

            client.startHandshake();

            // Verify that the server really speaks http/1.1

            OutputStream output = client.getOutputStream();
            output.write(("" +
                    "GET / HTTP/1.1\r\n" +
                    "Host: localhost:" + address.getPort() + "\r\n" +
                    "\r\n" +
                    "").getBytes(StandardCharsets.UTF_8));
            output.flush();

            InputStream input = client.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line = reader.readLine();
            Assert.assertTrue(line.contains(" 404 "));
        }
    }

    @Test
    public void testClientAdvertisingMultipleProtocolsServerSpeaksHTTPWhenNegotiated() throws Exception
    {
        InetSocketAddress address = prepare();

        SslContextFactory sslContextFactory = newSslContextFactory();
        sslContextFactory.start();
        SSLContext sslContext = sslContextFactory.getSslContext();
        try (SSLSocket client = (SSLSocket)sslContext.getSocketFactory().createSocket(address.getAddress(), address.getPort()))
        {
            client.setUseClientMode(true);
            client.setSoTimeout(5000);

            ALPN.put(client, new ALPN.ClientProvider()
            {
                @Override
                public void unsupported()
                {
                }

                @Override
                public List<String> protocols()
                {
                    return Arrays.asList("unknown/1.0", "http/1.1");
                }

                @Override
                public void selected(String protocol)
                {
                    Assert.assertEquals("http/1.1", protocol);
                }
            });

            client.startHandshake();

            // Verify that the server really speaks http/1.1

            OutputStream output = client.getOutputStream();
            output.write(("" +
                    "GET / HTTP/1.1\r\n" +
                    "Host: localhost:" + address.getPort() + "\r\n" +
                    "\r\n" +
                    "").getBytes(StandardCharsets.UTF_8));
            output.flush();

            InputStream input = client.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line = reader.readLine();
            Assert.assertTrue(line.contains(" 404 "));
        }
    }

    @Test
    public void testClientNotSupportingALPNServerSpeaksDefaultProtocol() throws Exception
    {
        InetSocketAddress address = prepare();

        SslContextFactory sslContextFactory = newSslContextFactory();
        sslContextFactory.start();
        SSLContext sslContext = sslContextFactory.getSslContext();
        try (SSLSocket client = (SSLSocket)sslContext.getSocketFactory().createSocket(address.getAddress(), address.getPort()))
        {
            client.setUseClientMode(true);
            client.setSoTimeout(5000);

            ALPN.put(client, new ALPN.ClientProvider()
            {
                @Override
                public void unsupported()
                {
                }

                @Override
                public List<String> protocols()
                {
                    return null;
                }

                @Override
                public void selected(String s)
                {
                }
            });

            client.startHandshake();

            // Verify that the server really speaks http/1.1

            OutputStream output = client.getOutputStream();
            output.write(("" +
                    "GET / HTTP/1.1\r\n" +
                    "Host: localhost:" + address.getPort() + "\r\n" +
                    "\r\n" +
                    "").getBytes(StandardCharsets.UTF_8));
            output.flush();

            InputStream input = client.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line = reader.readLine();
            Assert.assertTrue(line.contains(" 404 "));
        }
    }
}
