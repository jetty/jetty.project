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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.alpn.ALPN;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class ALPNNegotiationTest extends AbstractALPNTest
{
    @Test
    public void testClientAdvertisingHTTPServerSpeaksHTTP() throws Exception
    {
        InetSocketAddress address = prepare();
        connector.addConnectionFactory(new HttpConnectionFactory());

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
                public boolean supports()
                {
                    return true;
                }

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
        connector.addConnectionFactory(new HttpConnectionFactory());

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
                public boolean supports()
                {
                    return true;
                }

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
        connector.addConnectionFactory(new HttpConnectionFactory());

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
                public boolean supports()
                {
                    return false;
                }

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
