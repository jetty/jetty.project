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
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Test;

public class NPNNegotiationTest extends AbstractNPNTest
{
    @Test
    public void testServerAdvertisingHTTPSpeaksHTTP() throws Exception
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

            NextProtoNego.put(client, new NextProtoNego.ClientProvider()
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
                public String selectProtocol(List<String> strings)
                {
                    Assert.assertNotNull(strings);
                    String protocol = "http/1.1";
                    Assert.assertTrue(strings.contains(protocol));
                    return protocol;
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
    public void testServerAdvertisingSPDYAndHTTPSpeaksHTTPWhenNegotiated() throws Exception
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

            NextProtoNego.put(client, new NextProtoNego.ClientProvider()
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
                public String selectProtocol(List<String> strings)
                {
                    Assert.assertNotNull(strings);
                    String spdyProtocol = "spdy/2";
                    Assert.assertTrue(strings.contains(spdyProtocol));
                    String httpProtocol = "http/1.1";
                    Assert.assertTrue(strings.contains(httpProtocol));
                    Assert.assertTrue(strings.indexOf(spdyProtocol) < strings.indexOf(httpProtocol));
                    return httpProtocol;
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
    public void testServerAdvertisingSPDYAndHTTPSpeaksDefaultProtocolWhenNPNMissing() throws Exception
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

            NextProtoNego.put(client, new NextProtoNego.ClientProvider()
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
                public String selectProtocol(List<String> strings)
                {
                    return null;
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
