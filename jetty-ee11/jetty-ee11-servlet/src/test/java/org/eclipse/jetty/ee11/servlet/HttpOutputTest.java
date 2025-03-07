//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee11.servlet;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.IOResources;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.LocalConnector.LocalEndPoint;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 *
 */
public class HttpOutputTest
{
    public static final int OUTPUT_AGGREGATION_SIZE = 1024;
    public static final int OUTPUT_BUFFER_SIZE = 4096;
    private Server _server;
    private ServletContextHandler _servletContextHandler;
    private LocalConnector _connector;
    private ContentServlet _contentServlet;

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();
        _servletContextHandler = new ServletContextHandler("/");

        HttpConnectionFactory http = new HttpConnectionFactory();
        http.getHttpConfiguration().setRequestHeaderSize(1024);
        http.getHttpConfiguration().setResponseHeaderSize(1024);
        http.getHttpConfiguration().setOutputBufferSize(OUTPUT_BUFFER_SIZE);
        http.getHttpConfiguration().setOutputAggregationSize(OUTPUT_AGGREGATION_SIZE);

        _connector = new LocalConnector(_server, http, null);
        _server.addConnector(_connector);
        _server.setHandler(_servletContextHandler);

        _contentServlet = new ContentServlet();
        _servletContextHandler.addServlet(_contentServlet, "/*");
    }

    @AfterEach
    public void destroy() throws Exception
    {
        IO.close(_contentServlet._contentInputStream);
        IO.close(_contentServlet._contentChannel);
        _server.stop();
        _server.join();
    }

    @Test
    public void testSimple() throws Exception
    {
        _server.start();
        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
    }

    @Test
    public void testSendContentByteBuffer() throws Exception
    {
        _server.start();
        byte[] buffer = new byte[16 * 1024];
        Arrays.fill(buffer, 0, 4 * 1024, (byte)0x99);
        Arrays.fill(buffer, 4 * 1024, 12 * 1024, (byte)0x58);
        Arrays.fill(buffer, 12 * 1024, 16 * 1024, (byte)0x66);
        _contentServlet._content = ByteBuffer.wrap(buffer);
        _contentServlet._content.limit(12 * 1024);
        _contentServlet._content.position(4 * 1024);
        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("\r\nXXXXXXXXXXXXXXXXXXXXXXXXXXX"));

        for (int i = 0; i < 4 * 1024; i++)
        {
            assertEquals((byte)0x99, buffer[i], "i=" + i);
        }
        for (int i = 12 * 1024; i < 16 * 1024; i++)
        {
            assertEquals((byte)0x66, buffer[i], "i=" + i);
        }
    }

    @Test
    public void testSendInputStreamSimple() throws Exception
    {
        _server.start();
        Resource simple = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/simple.txt", false);
        _contentServlet._contentInputStream = IOResources.asInputStream(simple);
        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length: 11"));
    }

    @Test
    public void testSendInputStreamBig() throws Exception
    {
        _server.start();
        Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._contentInputStream = IOResources.asInputStream(big);
        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testSendInputStreamBigChunked() throws Exception
    {
        _server.start();
        Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._contentInputStream = new FilterInputStream(IOResources.asInputStream(big))
        {
            @Override
            public int read(byte[] b, int off, int len) throws IOException
            {
                return super.read(b, off, Math.min(len, 2000));
            }
        };
        LocalEndPoint endp = _connector.executeRequest(
            """
                GET / HTTP/1.1
                Host: localhost:80
                
                """);

        String response = endp.getResponse();

        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Transfer-Encoding: chunked"));
        assertThat(response, containsString("1\tThis is a big file"));
        assertThat(response, containsString("400\tThis is a big file"));
        assertThat(response, containsString("\r\n0\r\n"));

        endp.close();
    }

    @Test
    public void testSendChannelSimple() throws Exception
    {
        _server.start();
        Resource simple = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/simple.txt", false);
        _contentServlet._contentChannel = Files.newByteChannel(simple.getPath());
        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length: 11"));
    }

    @Test
    public void testSendChannelBig() throws Exception
    {
        _server.start();
        Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._contentChannel = Files.newByteChannel(big.getPath());
        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testSendResourceSimple() throws Exception
    {
        _server.start();
        Resource simple = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/simple.txt", false);
        _contentServlet._contentResource = simple;
        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length: 11"));
        assertThat(response, endsWith(toUTF8String(simple)));
    }

    @Test
    public void testSendResourceBig() throws Exception
    {
        _server.start();
        Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._contentResource = big;
        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testSendBigDirect() throws Exception
    {
        _server.start();
        Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length"));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testSendBigInDirect() throws Exception
    {
        _server.start();
        Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length"));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testSendChannelBigChunked() throws Exception
    {
        _server.start();
        Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        final ReadableByteChannel channel = Files.newByteChannel(big.getPath());
        _contentServlet._contentChannel = new ReadableByteChannel()
        {
            @Override
            public boolean isOpen()
            {
                return channel.isOpen();
            }

            @Override
            public void close() throws IOException
            {
                channel.close();
            }

            @Override
            public int read(ByteBuffer dst) throws IOException
            {
                int filled = 0;
                if (dst.position() == 0 && dst.limit() > 2000)
                {
                    int limit = dst.limit();
                    dst.limit(2000);
                    filled = channel.read(dst);
                    dst.limit(limit);
                }
                else
                    filled = channel.read(dst);
                return filled;
            }
        };

        LocalEndPoint endp = _connector.executeRequest("GET / HTTP/1.1\nHost: localhost:80\n\n");

        String response = endp.getResponse();
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Transfer-Encoding: chunked"));
        assertThat(response, containsString("1\tThis is a big file"));
        assertThat(response, containsString("400\tThis is a big file"));
        assertThat(response, containsString("\r\n0\r\n"));

        endp.close();
    }

    @Test
    public void testWriteByte() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = false;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._arrayBuffer = new byte[1];

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testWriteSmall() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = false;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._arrayBuffer = new byte[8];

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testWriteMed() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = false;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._arrayBuffer = new byte[4000];

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testWriteLarge() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = false;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._arrayBuffer = new byte[8192];

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testWriteByteKnown() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = true;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._arrayBuffer = new byte[1];

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length"));
        assertThat(response, endsWith(toUTF8String(big)));
        assertThat(_contentServlet._closedAfterWrite.get(10, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testWriteSmallKnown() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = true;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._arrayBuffer = new byte[8];

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length"));
        assertThat(response, endsWith(toUTF8String(big)));
        assertThat(_contentServlet._closedAfterWrite.get(10, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testWriteMedKnown() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = true;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._arrayBuffer = new byte[4000];

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length"));
        assertThat(response, endsWith(toUTF8String(big)));
        assertThat(_contentServlet._closedAfterWrite.get(10, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testWriteLargeKnown() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = true;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._arrayBuffer = new byte[8192];

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length"));
        assertThat(response, endsWith(toUTF8String(big)));
        assertThat(_contentServlet._closedAfterWrite.get(10, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testWriteHugeKnown() throws Exception
    {
        _server.start();
        _contentServlet._writeLengthIfKnown = true;
        _contentServlet._content = BufferUtil.allocate(4 * 1024 * 1024);
        _contentServlet._content.limit(_contentServlet._content.capacity());
        for (int i = _contentServlet._content.capacity(); i-- > 0; )
        {
            _contentServlet._content.put(i, (byte)'x');
        }
        _contentServlet._arrayBuffer = new byte[8192];

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length"));
        assertThat(_contentServlet._closedAfterWrite.get(10, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testWriteBufferSmall() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = false;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._byteBuffer = BufferUtil.allocate(8);

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
        assertThat(_contentServlet._closedAfterWrite.get(10, TimeUnit.SECONDS), is(false));
    }

    @Test
    public void testWriteBufferMed() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = false;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._byteBuffer = BufferUtil.allocate(4000);

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
        assertThat(_contentServlet._closedAfterWrite.get(10, TimeUnit.SECONDS), is(false));
    }

    @Test
    public void testWriteBufferLarge() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = false;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._byteBuffer = BufferUtil.allocate(8192);

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
        assertThat(_contentServlet._closedAfterWrite.get(10, TimeUnit.SECONDS), is(false));
    }

    @Test
    public void testWriteBufferSmallKnown() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = true;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._byteBuffer = BufferUtil.allocate(8);

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length"));
        assertThat(response, endsWith(toUTF8String(big)));
        assertThat(_contentServlet._closedAfterWrite.get(10, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testWriteBufferMedKnown() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = true;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._byteBuffer = BufferUtil.allocate(4000);

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length"));
        assertThat(response, endsWith(toUTF8String(big)));
        assertThat(_contentServlet._closedAfterWrite.get(10, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testWriteBufferLargeKnown() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = true;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._byteBuffer = BufferUtil.allocate(8192);

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length"));
        assertThat(response, endsWith(toUTF8String(big)));
        assertThat(_contentServlet._closedAfterWrite.get(10, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testAsyncWriteByte() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = false;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._arrayBuffer = new byte[1];
        _contentServlet._async = true;

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
        assertThat(_contentServlet._closedAfterWrite.get(10, TimeUnit.SECONDS), is(false));
    }

    @Test
    public void testAsyncWriteSmall() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = false;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._arrayBuffer = new byte[8];
        _contentServlet._async = true;

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
        assertThat(_contentServlet._closedAfterWrite.get(10, TimeUnit.SECONDS), is(false));
    }

    @Test
    public void testAsyncWriteMed() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = false;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._arrayBuffer = new byte[4000];
        _contentServlet._async = true;

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
        assertThat(_contentServlet._closedAfterWrite.get(10, TimeUnit.SECONDS), is(false));
    }

    @Test
    public void testAsyncWriteLarge() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = false;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._arrayBuffer = new byte[8192];
        _contentServlet._async = true;

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
        assertThat(_contentServlet._closedAfterWrite.get(10, TimeUnit.SECONDS), is(false));
    }

    @Test
    public void testAsyncWriteHuge() throws Exception
    {
        _server.start();
        _contentServlet._writeLengthIfKnown = false;
        _contentServlet._content = BufferUtil.allocate(4 * 1024 * 1024);
        _contentServlet._content.limit(_contentServlet._content.capacity());
        for (int i = _contentServlet._content.capacity(); i-- > 0; )
        {
            _contentServlet._content.put(i, (byte)'x');
        }
        _contentServlet._arrayBuffer = new byte[8192];
        _contentServlet._async = true;

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(_contentServlet._closedAfterWrite.get(10, TimeUnit.SECONDS), is(false));
    }

    @Test
    public void testAsyncWriteBufferSmall() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = false;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._byteBuffer = BufferUtil.allocate(8);
        _contentServlet._async = true;

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
        assertThat(_contentServlet._closedAfterWrite.get(10, TimeUnit.SECONDS), is(false));
    }

    @Test
    public void testAsyncWriteBufferMed() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = false;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._byteBuffer = BufferUtil.allocate(4000);
        _contentServlet._async = true;

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
        assertThat(_contentServlet._closedAfterWrite.get(10, TimeUnit.SECONDS), is(false));
    }

    @Test
    public void testAsyncWriteBufferLarge() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = false;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._byteBuffer = BufferUtil.allocate(8192);
        _contentServlet._async = true;

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
        assertThat(_contentServlet._closedAfterWrite.get(10, TimeUnit.SECONDS), is(false));
    }

    @Test
    public void testAsyncWriteBufferLargeDirect()
        throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = false;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, new ByteBufferPool.Sized(ByteBufferPool.SIZED_NON_POOLING, true, ByteBufferPool.SIZED_NON_POOLING.getSize())).getByteBuffer();
        _contentServlet._byteBuffer = BufferUtil.allocateDirect(8192);
        _contentServlet._async = true;

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
        assertThat(_contentServlet._closedAfterWrite.get(10, TimeUnit.SECONDS), is(false));
    }

    @Test
    public void testAsyncWriteBufferLargeHEAD() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/big.txt", false);
        _contentServlet._writeLengthIfKnown = false;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._byteBuffer = BufferUtil.allocate(8192);
        _contentServlet._async = true;

        int start = _contentServlet._owp.get();
        String response = _connector.getResponse("HEAD / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(_contentServlet._owp.get() - start, Matchers.greaterThan(0));
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, Matchers.not(containsString("1\tThis is a big file")));
        assertThat(response, Matchers.not(containsString("400\tThis is a big file")));
    }

    @Test
    public void testAsyncWriteSimpleKnown() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/simple.txt", false);

        _contentServlet._async = true;
        _contentServlet._writeLengthIfKnown = true;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._arrayBuffer = new byte[4000];

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length: 11"));
        assertThat(response, containsString("simple text"));
        assertThat(_contentServlet._closedAfterWrite.get(10, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testAsyncWriteSimpleKnownHEAD() throws Exception
    {
        _server.start();
        final Resource big = ResourceFactory.of(_servletContextHandler).newClassLoaderResource("simple/simple.txt", false);

        _contentServlet._async = true;
        _contentServlet._writeLengthIfKnown = true;
        _contentServlet._content = IOResources.toRetainableByteBuffer(big, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer();
        _contentServlet._arrayBuffer = new byte[4000];

        int start = _contentServlet._owp.get();
        String response = _connector.getResponse("HEAD / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(_contentServlet._owp.get() - start, Matchers.equalTo(1));
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length: 11"));
        assertThat(response, Matchers.not(containsString("simple text")));
    }

    @Test
    public void testEmptyArray() throws Exception
    {
        FuturePromise<Boolean> committed = new FuturePromise<>();
        HttpServlet servlet = new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setStatus(200);
                try
                {
                    response.getOutputStream().write(new byte[0]);
                    committed.succeeded(response.isCommitted());
                }
                catch (Throwable t)
                {
                    committed.failed(t);
                }
            }
        };
        _servletContextHandler.addServlet(servlet, "/test");

        _server.start();
        String response = _connector.getResponse("GET /test HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(committed.get(10, TimeUnit.SECONDS), is(false));
    }

    @Test
    public void testEmptyArrayKnown() throws Exception
    {
        FuturePromise<Boolean> committed = new FuturePromise<>();
        HttpServlet servlet = new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setStatus(200);
                response.setContentLength(0);
                try
                {
                    response.getOutputStream().write(new byte[0]);
                    committed.succeeded(response.isCommitted());
                }
                catch (Throwable t)
                {
                    committed.failed(t);
                }
            }
        };

        _servletContextHandler.addServlet(servlet, "/test");
        _server.start();
        String response = _connector.getResponse("GET /test HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length: 0"));
        assertThat(committed.get(10, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testEmptyBuffer() throws Exception
    {
        FuturePromise<Boolean> committed = new FuturePromise<>();
        HttpServlet servlet = new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setStatus(200);
                ((HttpOutput)response.getOutputStream()).write(ByteBuffer.wrap(new byte[0]));
                committed.succeeded(response.isCommitted());
            }
        };

        _servletContextHandler.addServlet(servlet, "/test");
        _server.start();
        String response = _connector.getResponse("GET /test HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(committed.get(10, TimeUnit.SECONDS), is(false));
    }

    @Test
    public void testEmptyBufferWithZeroContentLength() throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        HttpServlet servlet = new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setStatus(200);
                response.setContentLength(0);
                ((HttpOutput)response.getOutputStream()).write(ByteBuffer.wrap(new byte[0]));
                assertThat(response.isCommitted(), is(true));
                latch.countDown();
            }
        };

        _servletContextHandler.addServlet(servlet, "/test");
        _server.start();
        String response = _connector.getResponse("GET /test HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length: 0"));
        assertThat(latch.await(3, TimeUnit.SECONDS), is(true));
    }

    @Test
    public void testAggregation() throws Exception
    {
        AggregateServlet servlet = new AggregateServlet();
        _servletContextHandler.addServlet(servlet, "/test");
        _server.start();
        String response = _connector.getResponse("GET /test HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString(servlet.expected.toString()));
    }

    static class AggregateServlet extends HttpServlet
    {
        ByteArrayOutputStream expected = new ByteArrayOutputStream();

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            HttpOutput out = (HttpOutput)response.getOutputStream();

            int bufferSize = ServletContextRequest.getServletContextRequest(request).getRequest().getConnectionMetaData().getHttpConfiguration().getOutputBufferSize();
            int len = bufferSize * 3 / 2;

            byte[] data = new byte[OUTPUT_AGGREGATION_SIZE / 2 - 1];
            int fill = 0;
            while (expected.size() < len)
            {
                Arrays.fill(data, (byte)('A' + (fill++ % 26)));
                expected.write(data);
                out.write(data);
            }
        }
    }

    @Test
    public void testAsyncAggregation() throws Exception
    {
        AsyncAggregateServlet servlet = new AsyncAggregateServlet();
        _servletContextHandler.addServlet(servlet, "/test");
        _server.start();
        String response = _connector.getResponse("GET /test HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString(servlet.expected.toString()));
    }

    static class AsyncAggregateServlet extends HttpServlet
    {
        ByteArrayOutputStream expected = new ByteArrayOutputStream();

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            HttpOutput out = (HttpOutput)response.getOutputStream();

            int bufferSize = ServletContextRequest.getServletContextRequest(request).getRequest().getConnectionMetaData().getHttpConfiguration().getOutputBufferSize();
            int len = bufferSize * 3 / 2;

            AsyncContext async = request.startAsync();
            out.setWriteListener(new WriteListener()
            {
                int fill = 0;

                @Override
                public void onWritePossible() throws IOException
                {
                    byte[] data = new byte[OUTPUT_AGGREGATION_SIZE / 2 - 1];
                    while (out.isReady())
                    {
                        if (expected.size() >= len)
                        {
                            async.complete();
                            return;
                        }

                        Arrays.fill(data, (byte)('A' + (fill++ % 26)));
                        expected.write(data);
                        out.write(data);
                    }
                }

                @Override
                public void onError(Throwable t)
                {
                }
            });
        }
    }

    @Test
    public void testAggregateResidue() throws Exception
    {
        AggregateResidueServlet servlet = new AggregateResidueServlet();
        _servletContextHandler.addServlet(servlet, "/test");
        _server.start();
        String response = _connector.getResponse("GET /test HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString(servlet.expected.toString()));
    }

    static class AggregateResidueServlet extends HttpServlet
    {
        ByteArrayOutputStream expected = new ByteArrayOutputStream();

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            HttpOutput out = (HttpOutput)response.getOutputStream();

            int bufferSize = ServletContextRequest.getServletContextRequest(request).getRequest().getConnectionMetaData().getHttpConfiguration().getOutputBufferSize();
            int commitSize = ServletContextRequest.getServletContextRequest(request).getRequest().getConnectionMetaData().getHttpConfiguration().getOutputAggregationSize();
            char fill = 'A';

            // write data that will be aggregated
            byte[] data = new byte[commitSize - 1];
            Arrays.fill(data, (byte)(fill++));
            expected.write(data);
            out.write(data);
            int aggregated = data.length;

            // write data that will almost fill the aggregate buffer
            while (aggregated < (bufferSize - 1))
            {
                data = new byte[Math.min(commitSize - 1, bufferSize - aggregated - 1)];
                Arrays.fill(data, (byte)(fill++));
                expected.write(data);
                out.write(data);
                aggregated += data.length;
            }

            // write data that will not be aggregated because it is too large
            data = new byte[bufferSize + 1];
            Arrays.fill(data, (byte)(fill++));
            expected.write(data);
            out.write(data);
        }
    }

    @Test
    public void testPrint() throws Exception
    {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        PrintWriter exp = new PrintWriter(bout);
        HttpServlet servlet = new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setCharacterEncoding("UTF8");
                HttpOutput out = (HttpOutput)response.getOutputStream();

                // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
                exp.print("\u20AC\u0939\uD55C");
                out.print("\u20AC\u0939\uD55C");
                exp.print("zero");
                out.print("zero");
                exp.print(1);
                out.print(1);
                exp.print(2L);
                out.print(2L);
                exp.print(3.0F);
                out.print(3.0F);
                exp.print('4');
                out.print('4');
                exp.print(5.0D);
                out.print(5.0D);
                exp.print(true);
                out.print(true);
                exp.println("zero");
                out.println("zero");
                exp.println(-1);
                out.println(-1);
                exp.println(-2L);
                out.println(-2L);
                exp.println(-3.0F);
                out.println(-3.0F);
                exp.println('4');
                out.println('4');
                exp.println(-5.0D);
                out.println(-5.0D);
                exp.println(false);
                out.println(false);
            }
        };
        _servletContextHandler.addServlet(servlet, "/test");
        _server.start();
        String response = _connector.getResponse("GET /test HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString(bout.toString()));
    }

    @Test
    public void testReset() throws Exception
    {
        ByteArrayOutputStream exp = new ByteArrayOutputStream();
        HttpServlet servlet = new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                HttpOutput out = (HttpOutput)response.getOutputStream();

                out.setBufferSize(128);
                out.println("NOT TO BE SEEN!");
                out.resetBuffer();

                byte[] data = "TO BE SEEN\n".getBytes(StandardCharsets.ISO_8859_1);
                exp.write(data);
                out.write(data);

                out.flush();

                data = "Reset after flush\n".getBytes(StandardCharsets.ISO_8859_1);
                out.resetBuffer(); // Note that ISE would be thrown by ServletApiResponse.resetBuffer()
            }
        };
        _servletContextHandler.addServlet(servlet, "/test");
        _server.start();
        String response = _connector.getResponse("GET /test HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString(exp.toString()));
    }

    @Test
    public void testZeroLengthWrite() throws Exception
    {
        HttpServlet servlet = new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                response.setContentLength(0);
                AsyncContext async = request.startAsync();
                response.getOutputStream().setWriteListener(new WriteListener()
                {
                    @Override
                    public void onWritePossible() throws IOException
                    {
                        response.getOutputStream().write(new byte[0]);
                        async.complete();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                    }
                });
            }
        };
        _servletContextHandler.addServlet(servlet, "/test");
        _server.start();
        String response = _connector.getResponse("GET /test HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
    }

    private static String toUTF8String(Resource resource)
    {
        return BufferUtil.toUTF8String(IOResources.toRetainableByteBuffer(resource, ByteBufferPool.SIZED_NON_POOLING).getByteBuffer());
    }

    static class ContentServlet extends HttpServlet
    {
        AtomicInteger _owp = new AtomicInteger();
        boolean _writeLengthIfKnown = true;
        boolean _async;
        ByteBuffer _byteBuffer;
        byte[] _arrayBuffer;
        InputStream _contentInputStream;
        ReadableByteChannel _contentChannel;
        Resource _contentResource;
        ByteBuffer _content;
        final FuturePromise<Boolean> _closedAfterWrite = new FuturePromise<>();

        @Override
        protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
        {
            response.setContentType("text/plain");

            final HttpOutput out = (HttpOutput)response.getOutputStream();

            if (_contentInputStream != null)
            {
                out.sendContent(_contentInputStream);
                _contentInputStream = null;
                _closedAfterWrite.succeeded(out.isClosed());
                return;
            }

            if (_contentChannel != null)
            {
                out.sendContent(_contentChannel);
                _contentChannel = null;
                _closedAfterWrite.succeeded(out.isClosed());
                return;
            }

            if (_contentResource != null)
            {
                out.sendContent(_contentResource.newReadableByteChannel());
                _contentResource = null;
                _closedAfterWrite.succeeded(out.isClosed());
                return;
            }

            if (_content != null && _writeLengthIfKnown)
                response.setContentLength(_content.remaining());

            if (_arrayBuffer != null)
            {
                if (_async)
                {
                    final AsyncContext async = request.startAsync();
                    out.setWriteListener(new WriteListener()
                    {
                        @Override
                        public void onWritePossible() throws IOException
                        {
                            _owp.incrementAndGet();

                            while (out.isReady())
                            {
                                assertTrue(out.isReady());
                                int len = _content.remaining();
                                if (len > _arrayBuffer.length)
                                    len = _arrayBuffer.length;
                                if (len == 0)
                                {
                                    _closedAfterWrite.succeeded(out.isClosed());
                                    async.complete();
                                    break;
                                }

                                _content.get(_arrayBuffer, 0, len);
                                if (len == 1)
                                    out.write(_arrayBuffer[0]);
                                else
                                    out.write(_arrayBuffer, 0, len);
                            }
                        }

                        @Override
                        public void onError(Throwable t)
                        {
                            t.printStackTrace();
                            async.complete();
                        }
                    });

                    return;
                }

                while (BufferUtil.hasContent(_content))
                {
                    int len = _content.remaining();
                    if (len > _arrayBuffer.length)
                        len = _arrayBuffer.length;
                    _content.get(_arrayBuffer, 0, len);
                    if (len == 1)
                        out.write(_arrayBuffer[0]);
                    else
                        out.write(_arrayBuffer, 0, len);
                }
                _closedAfterWrite.succeeded(out.isClosed());
                return;
            }

            if (_byteBuffer != null)
            {
                if (_async)
                {
                    final AsyncContext async = request.startAsync();
                    out.setWriteListener(new WriteListener()
                    {
                        private boolean isFirstWrite = true;

                        @Override
                        public void onWritePossible() throws IOException
                        {
                            _owp.incrementAndGet();

                            while (out.isReady())
                            {
                                assertTrue(isFirstWrite || !_byteBuffer.hasRemaining());
                                assertTrue(out.isReady());
                                if (BufferUtil.isEmpty(_content))
                                {
                                    _closedAfterWrite.succeeded(out.isClosed());
                                    async.complete();
                                    break;
                                }

                                BufferUtil.clearToFill(_byteBuffer);
                                BufferUtil.put(_content, _byteBuffer);
                                BufferUtil.flipToFlush(_byteBuffer, 0);
                                out.write(_byteBuffer);
                                isFirstWrite = false;
                            }
                        }

                        @Override
                        public void onError(Throwable t)
                        {
                            t.printStackTrace();
                            async.complete();
                        }
                    });

                    return;
                }

                while (BufferUtil.hasContent(_content))
                {
                    BufferUtil.clearToFill(_byteBuffer);
                    BufferUtil.put(_content, _byteBuffer);
                    BufferUtil.flipToFlush(_byteBuffer, 0);
                    out.write(_byteBuffer);
                }
                _closedAfterWrite.succeeded(out.isClosed());
                return;
            }

            if (_content != null)
            {
                if (_content.hasArray())
                    out.write(_content.array(), _content.arrayOffset() + _content.position(), _content.remaining());
                else
                    out.sendContent(_content);
                _content = null;
                _closedAfterWrite.succeeded(out.isClosed());
            }
        }
    }
}


