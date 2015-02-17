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

package org.eclipse.jetty.http2.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.Promise;
import org.junit.Assert;
import org.junit.Test;

public class HTTP2Test extends AbstractTest
{
    @Test
    public void testRequestNoContentResponseNoContent() throws Exception
    {
        start(new EmptyHttpServlet());

        Session session = newClient(new Session.Listener.Adapter());

        HttpFields fields = new HttpFields();
        MetaData.Request metaData = newRequest("GET", fields);
        HeadersFrame frame = new HeadersFrame(1, metaData, null, true);
        final CountDownLatch latch = new CountDownLatch(1);
        session.newStream(frame, new Promise.Adapter<Stream>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                Assert.assertTrue(stream.isClosed());
                Assert.assertTrue(stream.getId() > 0);

                Assert.assertTrue(frame.isEndStream());
                Assert.assertEquals(stream.getId(), frame.getStreamId());
                Assert.assertTrue(frame.getMetaData().isResponse());
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                Assert.assertEquals(200, response.getStatus());

                latch.countDown();
            }
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testRequestNoContentResponseContent() throws Exception
    {
        final byte[] content = "Hello World!".getBytes(StandardCharsets.UTF_8);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                resp.getOutputStream().write(content);
            }
        });

        Session session = newClient(new Session.Listener.Adapter());

        HttpFields fields = new HttpFields();
        MetaData.Request metaData = newRequest("GET", fields);
        HeadersFrame frame = new HeadersFrame(1, metaData, null, true);
        final CountDownLatch latch = new CountDownLatch(2);
        session.newStream(frame, new Promise.Adapter<Stream>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                Assert.assertFalse(stream.isClosed());
                Assert.assertTrue(stream.getId() > 0);

                Assert.assertFalse(frame.isEndStream());
                Assert.assertEquals(stream.getId(), frame.getStreamId());
                Assert.assertTrue(frame.getMetaData().isResponse());
                MetaData.Response response = (MetaData.Response)frame.getMetaData();
                Assert.assertEquals(200, response.getStatus());

                latch.countDown();
            }

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                Assert.assertTrue(stream.isClosed());

                Assert.assertTrue(frame.isEndStream());
                Assert.assertEquals(ByteBuffer.wrap(content), frame.getData());

                callback.succeeded();
                latch.countDown();
            }
        });

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testMultipleRequests() throws Exception
    {
        final String downloadBytes = "X-Download";
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                int download = request.getIntHeader(downloadBytes);
                byte[] content = new byte[download];
                new Random().nextBytes(content);
                response.getOutputStream().write(content);
            }
        });

        int requests = 20;
        Session session = newClient(new Session.Listener.Adapter());

        Random random = new Random();
        HttpFields fields = new HttpFields();
        fields.putLongField(downloadBytes, random.nextInt(128 * 1024));
        fields.put("User-Agent", "HTTP2Client/" + Jetty.VERSION);
        MetaData.Request metaData = newRequest("GET", fields);
        HeadersFrame frame = new HeadersFrame(1, metaData, null, true);
        final CountDownLatch latch = new CountDownLatch(requests);
        for (int i = 0; i < requests; ++i)
        {
            session.newStream(frame, new Promise.Adapter<Stream>(), new Stream.Listener.Adapter()
            {
                @Override
                public void onData(Stream stream, DataFrame frame, Callback callback)
                {
                    callback.succeeded();
                    if (frame.isEndStream())
                        latch.countDown();
                }
            });
        }

        Assert.assertTrue(latch.await(requests, TimeUnit.SECONDS));
    }
}
