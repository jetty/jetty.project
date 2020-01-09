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

package org.eclipse.jetty.server;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.HttpOutput.Interceptor;
import org.eclipse.jetty.server.LocalConnector.LocalEndPoint;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HotSwapHandler;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.resource.Resource;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
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
    private LocalConnector _connector;
    private ContentHandler _handler;
    private HotSwapHandler _swap;

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();

        HttpConnectionFactory http = new HttpConnectionFactory();
        http.getHttpConfiguration().setRequestHeaderSize(1024);
        http.getHttpConfiguration().setResponseHeaderSize(1024);
        http.getHttpConfiguration().setOutputBufferSize(OUTPUT_BUFFER_SIZE);
        http.getHttpConfiguration().setOutputAggregationSize(OUTPUT_AGGREGATION_SIZE);

        _connector = new LocalConnector(_server, http, null);
        _server.addConnector(_connector);
        _swap = new HotSwapHandler();
        _handler = new ContentHandler();
        _swap.setHandler(_handler);
        _server.setHandler(_swap);
        _server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testSimple() throws Exception
    {
        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
    }

    @Test
    public void testByteUnknown() throws Exception
    {
        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
    }

    @Test
    public void testSendArray() throws Exception
    {
        byte[] buffer = new byte[16 * 1024];
        Arrays.fill(buffer, 0, 4 * 1024, (byte)0x99);
        Arrays.fill(buffer, 4 * 1024, 12 * 1024, (byte)0x58);
        Arrays.fill(buffer, 12 * 1024, 16 * 1024, (byte)0x66);
        _handler._content = ByteBuffer.wrap(buffer);
        _handler._content.limit(12 * 1024);
        _handler._content.position(4 * 1024);
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
        Resource simple = Resource.newClassPathResource("simple/simple.txt");
        _handler._contentInputStream = simple.getInputStream();
        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length: 11"));
    }

    @Test
    public void testSendInputStreamBig() throws Exception
    {
        Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._contentInputStream = big.getInputStream();
        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testSendInputStreamBigChunked() throws Exception
    {
        Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._contentInputStream = new FilterInputStream(big.getInputStream())
        {
            @Override
            public int read(byte[] b, int off, int len) throws IOException
            {
                int filled = super.read(b, off, len > 2000 ? 2000 : len);
                return filled;
            }
        };
        LocalEndPoint endp = _connector.executeRequest(
            "GET / HTTP/1.1\nHost: localhost:80\n\n" +
                "GET / HTTP/1.1\nHost: localhost:80\nConnection: close\n\n"
        );

        String response = endp.getResponse();

        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Transfer-Encoding: chunked"));
        assertThat(response, containsString("1\tThis is a big file"));
        assertThat(response, containsString("400\tThis is a big file"));
        assertThat(response, containsString("\r\n0\r\n"));

        response = endp.getResponse();
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Connection: close"));
    }

    @Test
    public void testSendChannelSimple() throws Exception
    {
        Resource simple = Resource.newClassPathResource("simple/simple.txt");
        _handler._contentChannel = simple.getReadableByteChannel();
        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length: 11"));
    }

    @Test
    public void testSendChannelBig() throws Exception
    {
        Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._contentChannel = big.getReadableByteChannel();
        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testSendBigDirect() throws Exception
    {
        Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._content = BufferUtil.toBuffer(big, true);
        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length"));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testSendBigInDirect() throws Exception
    {
        Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._content = BufferUtil.toBuffer(big, false);
        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length"));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testSendChannelBigChunked() throws Exception
    {
        Resource big = Resource.newClassPathResource("simple/big.txt");
        final ReadableByteChannel channel = big.getReadableByteChannel();
        _handler._contentChannel = new ReadableByteChannel()
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

        LocalEndPoint endp = _connector.executeRequest(
            "GET / HTTP/1.1\nHost: localhost:80\n\n" +
                "GET / HTTP/1.1\nHost: localhost:80\nConnection: close\n\n"
        );

        String response = endp.getResponse();
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Transfer-Encoding: chunked"));
        assertThat(response, containsString("1\tThis is a big file"));
        assertThat(response, containsString("400\tThis is a big file"));
        assertThat(response, containsString("\r\n0\r\n"));

        response = endp.getResponse();
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Connection: close"));
    }

    @Test
    public void testWriteByte() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._writeLengthIfKnown = false;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._arrayBuffer = new byte[1];

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testWriteSmall() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._writeLengthIfKnown = false;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._arrayBuffer = new byte[8];

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testWriteMed() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._writeLengthIfKnown = false;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._arrayBuffer = new byte[4000];

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testWriteLarge() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._writeLengthIfKnown = false;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._arrayBuffer = new byte[8192];

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testWriteByteKnown() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._writeLengthIfKnown = true;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._arrayBuffer = new byte[1];

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length"));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testWriteSmallKnown() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._writeLengthIfKnown = true;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._arrayBuffer = new byte[8];

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length"));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testWriteMedKnown() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._writeLengthIfKnown = true;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._arrayBuffer = new byte[4000];

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length"));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testWriteLargeKnown() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._writeLengthIfKnown = true;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._arrayBuffer = new byte[8192];

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length"));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testWriteHugeKnown() throws Exception
    {
        _handler._writeLengthIfKnown = true;
        _handler._content = BufferUtil.allocate(4 * 1024 * 1024);
        _handler._content.limit(_handler._content.capacity());
        for (int i = _handler._content.capacity(); i-- > 0; )
        {
            _handler._content.put(i, (byte)'x');
        }
        _handler._arrayBuffer = new byte[8192];

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length"));
    }

    @Test
    public void testWriteBufferSmall() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._writeLengthIfKnown = false;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._byteBuffer = BufferUtil.allocate(8);

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testWriteBufferMed() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._writeLengthIfKnown = false;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._byteBuffer = BufferUtil.allocate(4000);

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testWriteBufferLarge() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._writeLengthIfKnown = false;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._byteBuffer = BufferUtil.allocate(8192);

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testAsyncWriteByte() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._writeLengthIfKnown = false;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._arrayBuffer = new byte[1];
        _handler._async = true;

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testAsyncWriteSmall() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._writeLengthIfKnown = false;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._arrayBuffer = new byte[8];
        _handler._async = true;

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testAsyncWriteMed() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._writeLengthIfKnown = false;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._arrayBuffer = new byte[4000];
        _handler._async = true;

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testAsyncWriteLarge() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._writeLengthIfKnown = false;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._arrayBuffer = new byte[8192];
        _handler._async = true;

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testAsyncWriteHuge() throws Exception
    {
        _handler._writeLengthIfKnown = true;
        _handler._content = BufferUtil.allocate(4 * 1024 * 1024);
        _handler._content.limit(_handler._content.capacity());
        for (int i = _handler._content.capacity(); i-- > 0; )
        {
            _handler._content.put(i, (byte)'x');
        }
        _handler._arrayBuffer = new byte[8192];
        _handler._async = true;

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length"));
    }

    @Test
    public void testAsyncWriteBufferSmall() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._writeLengthIfKnown = false;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._byteBuffer = BufferUtil.allocate(8);
        _handler._async = true;

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testAsyncWriteBufferMed() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._writeLengthIfKnown = false;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._byteBuffer = BufferUtil.allocate(4000);
        _handler._async = true;

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testAsyncWriteBufferLarge() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._writeLengthIfKnown = false;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._byteBuffer = BufferUtil.allocate(8192);
        _handler._async = true;

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testAsyncWriteBufferLargeDirect()
        throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._writeLengthIfKnown = false;
        _handler._content = BufferUtil.toBuffer(big, true);
        _handler._byteBuffer = BufferUtil.allocateDirect(8192);
        _handler._async = true;

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, endsWith(toUTF8String(big)));
    }

    @Test
    public void testAsyncWriteBufferLargeHEAD() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._writeLengthIfKnown = false;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._byteBuffer = BufferUtil.allocate(8192);
        _handler._async = true;

        int start = _handler._owp.get();
        String response = _connector.getResponse("HEAD / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(_handler._owp.get() - start, Matchers.greaterThan(0));
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, Matchers.not(containsString("1\tThis is a big file")));
        assertThat(response, Matchers.not(containsString("400\tThis is a big file")));
    }

    @Test
    public void testAsyncWriteSimpleKnown() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/simple.txt");

        _handler._async = true;
        _handler._writeLengthIfKnown = true;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._arrayBuffer = new byte[4000];

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length: 11"));
        assertThat(response, containsString("simple text"));
    }

    @Test
    public void testAsyncWriteSimpleKnownHEAD() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/simple.txt");

        _handler._async = true;
        _handler._writeLengthIfKnown = true;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._arrayBuffer = new byte[4000];

        int start = _handler._owp.get();
        String response = _connector.getResponse("HEAD / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(_handler._owp.get() - start, Matchers.equalTo(1));
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("Content-Length: 11"));
        assertThat(response, Matchers.not(containsString("simple text")));
    }

    @Test
    public void testWriteInterception() throws Exception
    {
        final Resource big = Resource.newClassPathResource("simple/big.txt");
        _handler._writeLengthIfKnown = false;
        _handler._content = BufferUtil.toBuffer(big, false);
        _handler._arrayBuffer = new byte[1024];
        _handler._interceptor = new ChainedInterceptor()
        {
            Interceptor _next;

            @Override
            public void write(ByteBuffer content, boolean complete, Callback callback)
            {
                String s = BufferUtil.toString(content).toUpperCase().replaceAll("BIG", "BIGGER");
                _next.write(BufferUtil.toBuffer(s), complete, callback);
            }

            @Override
            public boolean isOptimizedForDirectBuffers()
            {
                return _next.isOptimizedForDirectBuffers();
            }

            @Override
            public Interceptor getNextInterceptor()
            {
                return _next;
            }

            @Override
            public void setNext(Interceptor interceptor)
            {
                _next = interceptor;
            }
        };

        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, Matchers.not(containsString("Content-Length")));
        assertThat(response, containsString("400\tTHIS IS A BIGGER FILE"));
    }

    @Test
    public void testAggregation() throws Exception
    {
        AggregateHandler handler = new AggregateHandler();
        _swap.setHandler(handler);
        handler.start();
        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString(handler.expected.toString()));
    }

    static class AggregateHandler extends AbstractHandler
    {
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            HttpOutput out = (HttpOutput) response.getOutputStream();

            // Add interceptor to check aggregation is done
            HttpOutput.Interceptor interceptor = out.getInterceptor();
            out.setInterceptor(new AggregationChecker(interceptor));

            int bufferSize = baseRequest.getHttpChannel().getHttpConfiguration().getOutputBufferSize();
            int len = bufferSize * 3 / 2;

            byte[] data = new byte[AggregationChecker.MAX_SIZE];
            int fill = 0;
            while (expected.size() < len)
            {
                Arrays.fill(data, (byte)('A' + (fill++%26)));
                expected.write(data);
                out.write(data);
            }
        }
    }

    @Test
    public void testAsyncAggregation() throws Exception
    {
        AsyncAggregateHandler handler = new AsyncAggregateHandler();
        _swap.setHandler(handler);
        handler.start();
        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString(handler.expected.toString()));
    }

    static class AsyncAggregateHandler extends AbstractHandler
    {
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            HttpOutput out = (HttpOutput) response.getOutputStream();

            // Add interceptor to check aggregation is done
            HttpOutput.Interceptor interceptor = out.getInterceptor();
            out.setInterceptor(new AggregationChecker(interceptor));

            int bufferSize = baseRequest.getHttpChannel().getHttpConfiguration().getOutputBufferSize();
            int len = bufferSize * 3 / 2;

            AsyncContext async = request.startAsync();
            out.setWriteListener(new WriteListener()
            {
                int fill = 0;
                @Override
                public void onWritePossible() throws IOException
                {
                    byte[] data = new byte[AggregationChecker.MAX_SIZE];
                    while(out.isReady())
                    {
                        if (expected.size() >= len)
                        {
                            async.complete();
                            return;
                        }

                        Arrays.fill(data, (byte)('A' + (fill++%26)));
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

    private static class AggregationChecker implements Interceptor
    {
        static final int MAX_SIZE = OUTPUT_AGGREGATION_SIZE / 2 - 1;
        private final Interceptor interceptor;

        public AggregationChecker(Interceptor interceptor)
        {
            this.interceptor = interceptor;
        }

        @Override
        public void write(ByteBuffer content, boolean last, Callback callback)
        {
            if (content.remaining() <= MAX_SIZE)
                throw new IllegalStateException("Not Aggregated!");
            interceptor.write(content, last, callback);
        }

        @Override
        public Interceptor getNextInterceptor()
        {
            return interceptor;
        }

        @Override
        public boolean isOptimizedForDirectBuffers()
        {
            return interceptor.isOptimizedForDirectBuffers();
        }
    }

    @Test
    public void testAggregateResidue() throws Exception
    {
        AggregateResidueHandler handler = new AggregateResidueHandler();
        _swap.setHandler(handler);
        handler.start();
        String response = _connector.getResponse("GET / HTTP/1.0\nHost: localhost:80\n\n");
        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString(handler.expected.toString()));
    }

    static class AggregateResidueHandler extends AbstractHandler
    {
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            HttpOutput out = (HttpOutput) response.getOutputStream();

            int bufferSize = baseRequest.getHttpChannel().getHttpConfiguration().getOutputBufferSize();
            int commitSize = baseRequest.getHttpChannel().getHttpConfiguration().getOutputAggregationSize();
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

            // write data that will not be aggregated
            data = new byte[bufferSize + 1];
            Arrays.fill(data, (byte)(fill++));
            expected.write(data);
            out.write(data);
        }
    }


    private static String toUTF8String(Resource resource)
        throws IOException
    {
        return BufferUtil.toUTF8String(BufferUtil.toBuffer(resource, false));
    }

    interface ChainedInterceptor extends HttpOutput.Interceptor
    {
        default void init(Request baseRequest)
        {
        }

        void setNext(Interceptor interceptor);
    }

    static class ContentHandler extends AbstractHandler
    {
        AtomicInteger _owp = new AtomicInteger();
        boolean _writeLengthIfKnown = true;
        boolean _async;
        ByteBuffer _byteBuffer;
        byte[] _arrayBuffer;
        InputStream _contentInputStream;
        ReadableByteChannel _contentChannel;
        ByteBuffer _content;
        ChainedInterceptor _interceptor;

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            baseRequest.setHandled(true);
            if (_interceptor != null)
            {
                _interceptor.init(baseRequest);
                _interceptor.setNext(baseRequest.getResponse().getHttpOutput().getInterceptor());
                baseRequest.getResponse().getHttpOutput().setInterceptor(_interceptor);
            }

            response.setContentType("text/plain");

            final HttpOutput out = (HttpOutput)response.getOutputStream();

            if (_contentInputStream != null)
            {
                out.sendContent(_contentInputStream);
                _contentInputStream = null;
                return;
            }

            if (_contentChannel != null)
            {
                out.sendContent(_contentChannel);
                _contentChannel = null;
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
                                    async.complete();
                                    break;
                                }

                                _content.get(_arrayBuffer, 0, len);
                                if (len == 1)
                                    out.write(_arrayBuffer[0]);
                                else
                                    out.write(_arrayBuffer, 0, len);
                            }
                            // assertFalse(out.isReady());
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

                return;
            }

            if (_content != null)
            {
                if (_content.hasArray())
                    out.write(_content.array(), _content.arrayOffset() + _content.position(), _content.remaining());
                else
                    out.sendContent(_content);
                _content = null;
                return;
            }
        }
    }
}


