/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy.http;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.spdy.AsyncConnectionFactory;
import org.eclipse.jetty.spdy.SPDYServerConnector;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatchman;
import org.junit.runners.model.FrameworkMethod;

public class ProtocolNegotiationTest
{
    @Rule
    public final TestWatchman testName = new TestWatchman()
    {
        @Override
        public void starting(FrameworkMethod method)
        {
            super.starting(method);
            System.err.printf("Running %s.%s()%n",
                    method.getMethod().getDeclaringClass().getName(),
                    method.getName());
        }
    };

    protected Server server;
    protected SPDYServerConnector connector;

    protected InetSocketAddress startServer(SPDYServerConnector connector) throws Exception
    {
        server = new Server();
        if (connector == null)
            connector = new SPDYServerConnector(null, newSslContextFactory());
        connector.setPort(0);
        this.connector = connector;
        server.addConnector(connector);
        server.start();
        return new InetSocketAddress("localhost", connector.getLocalPort());
    }

    protected SslContextFactory newSslContextFactory()
    {
        SslContextFactory sslContextFactory = new SslContextFactory();
        sslContextFactory.setKeyStorePath("src/test/resources/keystore.jks");
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.setTrustStore("src/test/resources/truststore.jks");
        sslContextFactory.setTrustStorePassword("storepwd");
        sslContextFactory.setProtocol("TLSv1");
        sslContextFactory.setIncludeProtocols("TLSv1");
        return sslContextFactory;
    }

    @Test
    public void testServerAdvertisingHTTPSpeaksHTTP() throws Exception
    {
        InetSocketAddress address = startServer(null);
        connector.removeAsyncConnectionFactory("spdy/2");
        connector.putAsyncConnectionFactory("http/1.1", new ServerHTTPAsyncConnectionFactory(connector));

        SslContextFactory sslContextFactory = newSslContextFactory();
        sslContextFactory.start();
        SSLContext sslContext = sslContextFactory.getSslContext();
        SSLSocket client = (SSLSocket)sslContext.getSocketFactory().createSocket(address.getAddress(), address.getPort());
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
                Assert.assertEquals(1, strings.size());
                String protocol = strings.get(0);
                Assert.assertEquals("http/1.1", protocol);
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
                "").getBytes("UTF-8"));
        output.flush();

        InputStream input = client.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
        String line = reader.readLine();
        Assert.assertTrue(line.contains(" 404 "));

        client.close();
    }

    @Test
    public void testServerAdvertisingSPDYAndHTTPSpeaksHTTPWhenNegotiated() throws Exception
    {
        InetSocketAddress address = startServer(null);
        connector.putAsyncConnectionFactory("http/1.1", new ServerHTTPAsyncConnectionFactory(connector));

        SslContextFactory sslContextFactory = newSslContextFactory();
        sslContextFactory.start();
        SSLContext sslContext = sslContextFactory.getSslContext();
        SSLSocket client = (SSLSocket)sslContext.getSocketFactory().createSocket(address.getAddress(), address.getPort());
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
                Assert.assertEquals(2, strings.size());
                String spdyProtocol = strings.get(0);
                Assert.assertEquals("spdy/2", spdyProtocol);
                String httpProtocol = strings.get(1);
                Assert.assertEquals("http/1.1", httpProtocol);
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
                "").getBytes("UTF-8"));
        output.flush();

        InputStream input = client.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
        String line = reader.readLine();
        Assert.assertTrue(line.contains(" 404 "));

        client.close();
    }

    @Test
    public void testServerAdvertisingSPDYAndHTTPSpeaksDefaultProtocolWhenNPNMissing() throws Exception
    {
        InetSocketAddress address = startServer(new SPDYServerConnector(null, newSslContextFactory())
        {
            @Override
            protected AsyncConnectionFactory getDefaultAsyncConnectionFactory()
            {
                return new ServerHTTPAsyncConnectionFactory(connector);
            }
        });
        connector.putAsyncConnectionFactory("http/1.1", new ServerHTTPAsyncConnectionFactory(connector));

        SslContextFactory sslContextFactory = newSslContextFactory();
        sslContextFactory.start();
        SSLContext sslContext = sslContextFactory.getSslContext();
        SSLSocket client = (SSLSocket)sslContext.getSocketFactory().createSocket(address.getAddress(), address.getPort());
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
                "").getBytes("UTF-8"));
        output.flush();

        InputStream input = client.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
        String line = reader.readLine();
        Assert.assertTrue(line.contains(" 404 "));

        client.close();
    }

}
