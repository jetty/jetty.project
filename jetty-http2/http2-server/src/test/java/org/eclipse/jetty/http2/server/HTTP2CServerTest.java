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

package org.eclipse.jetty.http2.server;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.generator.Generator;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConnection;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class HTTP2CServerTest extends AbstractServerTest
{
    @Before
    public void before() throws Exception
    {
        server = new HTTP2CServer(0);
        server.start();
        connector = (ServerConnector)server.getConnectors()[0];
    }

    @After
    public void after() throws Exception
    {
        server.stop();
    }

    @Test
    public void testHTTP_1_0_Simple() throws Exception
    {
        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            client.getOutputStream().write("GET / HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            client.getOutputStream().flush();

            String response = IO.toString(client.getInputStream());

            assertThat(response, containsString("HTTP/1.1 200 OK"));
            assertThat(response, containsString("Hello from Jetty using HTTP/1.0"));
        }
    }

    @Test
    public void testHTTP_1_1_Simple() throws Exception
    {
        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            client.getOutputStream().write("GET /one HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            client.getOutputStream().write("GET /two HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            client.getOutputStream().flush();

            String response = IO.toString(client.getInputStream());

            assertThat(response, containsString("HTTP/1.1 200 OK"));
            assertThat(response, containsString("Hello from Jetty using HTTP/1.1"));
            assertThat(response, containsString("uri=/one"));
            assertThat(response, containsString("uri=/two"));
        }
    }

    @Test
    public void testHTTP_1_1_Upgrade() throws Exception
    {
        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            output.write(("" +
                    "GET /one HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Connection: something, else, upgrade, HTTP2-Settings\r\n" +
                    "Upgrade: h2c\r\n" +
                    "HTTP2-Settings: \r\n" +
                    "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            output.flush();

            InputStream input = client.getInputStream();
            Utf8StringBuilder upgrade = new Utf8StringBuilder();
            int crlfs = 0;
            while (true)
            {
                int read = input.read();
                if (read == '\r' || read == '\n')
                    ++crlfs;
                else
                    crlfs = 0;
                upgrade.append((byte)read);
                if (crlfs == 4)
                    break;
            }

            assertTrue(upgrade.toString().startsWith("HTTP/1.1 101 "));

            byteBufferPool = new MappedByteBufferPool();
            generator = new Generator(byteBufferPool);

            final AtomicReference<HeadersFrame> headersRef = new AtomicReference<>();
            final AtomicReference<DataFrame> dataRef = new AtomicReference<>();
            final AtomicReference<CountDownLatch> latchRef = new AtomicReference<>(new CountDownLatch(2));
            Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
            {
                @Override
                public void onHeaders(HeadersFrame frame)
                {
                    headersRef.set(frame);
                    latchRef.get().countDown();
                }

                @Override
                public void onData(DataFrame frame)
                {
                    dataRef.set(frame);
                    latchRef.get().countDown();
                }
            }, 4096, 8192);

            parseResponse(client, parser);

            Assert.assertTrue(latchRef.get().await(5, TimeUnit.SECONDS));

            HeadersFrame response = headersRef.get();
            Assert.assertNotNull(response);
            MetaData.Response responseMetaData = (MetaData.Response)response.getMetaData();
            Assert.assertEquals(200, responseMetaData.getStatus());

            DataFrame responseData = dataRef.get();
            Assert.assertNotNull(responseData);

            String content = BufferUtil.toString(responseData.getData());

            // The upgrade request is seen as HTTP/1.1.
            assertThat(content, containsString("Hello from Jetty using HTTP/1.1"));
            assertThat(content, containsString("uri=/one"));

            // Send a HTTP/2 request.
            headersRef.set(null);
            dataRef.set(null);
            latchRef.set(new CountDownLatch(2));
            ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
            generator.control(lease, new PrefaceFrame());
            generator.control(lease, new SettingsFrame(new HashMap<>(), false));
            MetaData.Request metaData = new MetaData.Request("GET", HttpScheme.HTTP, new HostPortHttpField("localhost:" + connector.getLocalPort()), "/two", HttpVersion.HTTP_2, new HttpFields());
            generator.control(lease, new HeadersFrame(3, metaData, null, true));
            for (ByteBuffer buffer : lease.getByteBuffers())
                output.write(BufferUtil.toArray(buffer));
            output.flush();

            parseResponse(client, parser);

            Assert.assertTrue(latchRef.get().await(5, TimeUnit.SECONDS));

            response = headersRef.get();
            Assert.assertNotNull(response);
            responseMetaData = (MetaData.Response)response.getMetaData();
            Assert.assertEquals(200, responseMetaData.getStatus());

            responseData = dataRef.get();
            Assert.assertNotNull(responseData);

            content = BufferUtil.toString(responseData.getData());

            assertThat(content, containsString("Hello from Jetty using HTTP/2.0"));
            assertThat(content, containsString("uri=/two"));
        }
    }

    @Test
    public void testHTTP_2_0_Direct() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(3);

        byteBufferPool = new MappedByteBufferPool();
        generator = new Generator(byteBufferPool);

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.control(lease, new PrefaceFrame());
        generator.control(lease, new SettingsFrame(new HashMap<>(), false));
        MetaData.Request metaData = new MetaData.Request("GET", HttpScheme.HTTP, new HostPortHttpField("localhost:" + connector.getLocalPort()), "/test", HttpVersion.HTTP_2, new HttpFields());
        generator.control(lease, new HeadersFrame(1, metaData, null, true));

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                output.write(BufferUtil.toArray(buffer));
            }

            final AtomicReference<HeadersFrame> headersRef = new AtomicReference<>();
            final AtomicReference<DataFrame> dataRef = new AtomicReference<>();
            Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
            {
                @Override
                public void onSettings(SettingsFrame frame)
                {
                    latch.countDown();
                }

                @Override
                public void onHeaders(HeadersFrame frame)
                {
                    headersRef.set(frame);
                    latch.countDown();
                }

                @Override
                public void onData(DataFrame frame)
                {
                    dataRef.set(frame);
                    latch.countDown();
                }
            }, 4096, 8192);

            parseResponse(client, parser);

            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

            HeadersFrame response = headersRef.get();
            Assert.assertNotNull(response);
            MetaData.Response responseMetaData = (MetaData.Response)response.getMetaData();
            Assert.assertEquals(200, responseMetaData.getStatus());

            DataFrame responseData = dataRef.get();
            Assert.assertNotNull(responseData);

            String s = BufferUtil.toString(responseData.getData());

            assertThat(s, containsString("Hello from Jetty using HTTP/2.0"));
            assertThat(s, containsString("uri=/test"));
        }
    }

    @Test
    public void testHTTP_2_0_DirectWithoutH2C() throws Exception
    {
        AtomicLong fills = new AtomicLong();
        // Remove "h2c", leaving only "http/1.1".
        connector.clearConnectionFactories();
        HttpConnectionFactory connectionFactory = new HttpConnectionFactory()
        {
            @Override
            public Connection newConnection(Connector connector, EndPoint endPoint)
            {
                HttpConnection connection = new HttpConnection(getHttpConfiguration(), connector, endPoint,getHttpCompliance(),isRecordHttpComplianceViolations())
                {
                    @Override
                    public void onFillable()
                    {
                        fills.incrementAndGet();
                        super.onFillable();
                    }
                };
                return configure(connection, connector, endPoint);
            }
        };
        connector.addConnectionFactory(connectionFactory);
        connector.setDefaultProtocol(connectionFactory.getProtocol());

        // Now send a HTTP/2 direct request, which
        // will have the PRI * HTTP/2.0 preface.

        byteBufferPool = new MappedByteBufferPool();
        generator = new Generator(byteBufferPool);

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.control(lease, new PrefaceFrame());

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            for (ByteBuffer buffer : lease.getByteBuffers())
                output.write(BufferUtil.toArray(buffer));

            // We sent a HTTP/2 preface, but the server has no "h2c" connection
            // factory so it does not know how to handle this request.

            InputStream input = client.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String responseLine = reader.readLine();
            Assert.assertThat(responseLine, Matchers.containsString(" 426 "));
            while (true)
            {
                if (reader.read() < 0)
                    break;
            }
        }

        // Make sure we did not spin.
        Thread.sleep(1000);
        Assert.assertThat(fills.get(), Matchers.lessThan(5L));
    }
}
