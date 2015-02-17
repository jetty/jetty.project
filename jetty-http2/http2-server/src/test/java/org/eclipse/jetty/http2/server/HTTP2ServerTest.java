//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.http2.frames.PrefaceFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.Assert;
import org.junit.Test;

public class HTTP2ServerTest extends AbstractServerTest
{
    @Test
    public void testNoPrefaceBytes() throws Exception
    {
        startServer(new HttpServlet(){});

        // No preface bytes.
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.control(lease, new HeadersFrame(1, metaData, null, true));

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                output.write(BufferUtil.toArray(buffer));
            }

            final CountDownLatch latch = new CountDownLatch(1);
            Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
            {
                @Override
                public void onGoAway(GoAwayFrame frame)
                {
                    latch.countDown();
                }
            }, 4096, 8192);

            parseResponse(client, parser);

            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testRequestResponseNoContent() throws Exception
    {
        final CountDownLatch latch = new CountDownLatch(3);
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                latch.countDown();
            }
        });

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.control(lease, new PrefaceFrame());
        MetaData.Request metaData = newRequest("GET", new HttpFields());
        generator.control(lease, new HeadersFrame(1, metaData, null, true));

        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                output.write(BufferUtil.toArray(buffer));
            }

            final AtomicReference<HeadersFrame> frameRef = new AtomicReference<>();
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
                    frameRef.set(frame);
                    latch.countDown();
                }
            }, 4096, 8192);

            parseResponse(client, parser);

            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

            HeadersFrame response = frameRef.get();
            Assert.assertNotNull(response);
            MetaData.Response responseMetaData = (MetaData.Response)response.getMetaData();
            Assert.assertEquals(200, responseMetaData.getStatus());
        }
    }

    @Test
    public void testRequestResponseContent() throws Exception
    {
        final byte[] content = "Hello, world!".getBytes(StandardCharsets.UTF_8);
        final CountDownLatch latch = new CountDownLatch(4);
        startServer(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                latch.countDown();
                resp.getOutputStream().write(content);
            }
        });

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.control(lease, new PrefaceFrame());
        MetaData.Request metaData = newRequest("GET", new HttpFields());
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
            Assert.assertArrayEquals(content, BufferUtil.toArray(responseData.getData()));
        }
    }

    @Test
    public void testBadPingWrongPayload() throws Exception
    {
        startServer(new HttpServlet(){});

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.control(lease, new PrefaceFrame());
        generator.control(lease, new PingFrame(new byte[8], false));
        // Modify the length of the frame to a wrong one.
        lease.getByteBuffers().get(1).putShort(0, (short)7);

        final CountDownLatch latch = new CountDownLatch(1);
        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                output.write(BufferUtil.toArray(buffer));
            }

            Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
            {
                @Override
                public void onGoAway(GoAwayFrame frame)
                {
                    Assert.assertEquals(ErrorCode.FRAME_SIZE_ERROR.code, frame.getError());
                    latch.countDown();
                }
            }, 4096, 8192);

            parseResponse(client, parser);

            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void testBadPingWrongStreamId() throws Exception
    {
        startServer(new HttpServlet(){});

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.control(lease, new PrefaceFrame());
        generator.control(lease, new PingFrame(new byte[8], false));
        // Modify the streamId of the frame to non zero.
        lease.getByteBuffers().get(1).putInt(4, 1);

        final CountDownLatch latch = new CountDownLatch(1);
        try (Socket client = new Socket("localhost", connector.getLocalPort()))
        {
            OutputStream output = client.getOutputStream();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                output.write(BufferUtil.toArray(buffer));
            }

            Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
            {
                @Override
                public void onGoAway(GoAwayFrame frame)
                {
                    Assert.assertEquals(ErrorCode.PROTOCOL_ERROR.code, frame.getError());
                    latch.countDown();
                }
            }, 4096, 8192);

            parseResponse(client, parser);

            Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        }
    }
}
