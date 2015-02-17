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
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.servlet.DispatcherType;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PushPromiseFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlets.PushCacheFilter;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Promise;
import org.junit.Assert;
import org.junit.Test;

public class PushCacheFilterTest extends AbstractTest
{
    @Override
    protected void customizeContext(ServletContextHandler context)
    {
        context.addFilter(PushCacheFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));
    }

    @Test
    public void testPush() throws Exception
    {
        final String primaryResource = "/primary.html";
        final String secondaryResource = "/secondary.png";
        final byte[] secondaryData = "SECONDARY".getBytes("UTF-8");
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                String requestURI = req.getRequestURI();
                ServletOutputStream output = resp.getOutputStream();
                if (requestURI.endsWith(primaryResource))
                    output.print("<html><head></head><body>PRIMARY</body></html>");
                else if (requestURI.endsWith(secondaryResource))
                    output.write(secondaryData);
            }
        });

        final Session session = newClient(new Session.Listener.Adapter());

        // Request for the primary and secondary resource to build the cache.
        final String primaryURI = "http://localhost:" + connector.getLocalPort() + servletPath + primaryResource;
        HttpFields primaryFields = new HttpFields();
        MetaData.Request primaryRequest = newRequest("GET", primaryResource, primaryFields);
        final CountDownLatch warmupLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(0, primaryRequest, null, true), new Promise.Adapter<Stream>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                {
                    // Request for the secondary resource.
                    HttpFields secondaryFields = new HttpFields();
                    secondaryFields.put(HttpHeader.REFERER, primaryURI);
                    MetaData.Request secondaryRequest = newRequest("GET", secondaryResource, secondaryFields);
                    session.newStream(new HeadersFrame(0, secondaryRequest, null, true), new Promise.Adapter<Stream>(), new Stream.Listener.Adapter()
                    {
                        @Override
                        public void onData(Stream stream, DataFrame frame, Callback callback)
                        {
                            warmupLatch.countDown();
                        }
                    });
                }
            }
        });
        Assert.assertTrue(warmupLatch.await(5, TimeUnit.SECONDS));

        // Request again the primary resource, we should get the secondary resource pushed.
        primaryRequest = newRequest("GET", primaryResource, primaryFields);
        final CountDownLatch primaryResponseLatch = new CountDownLatch(1);
        final CountDownLatch pushLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(0, primaryRequest, null, true), new Promise.Adapter<Stream>(), new Stream.Listener.Adapter()
        {
            @Override
            public Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
            {
                return new Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        callback.succeeded();
                        if (frame.isEndStream())
                            pushLatch.countDown();
                    }
                };
            }

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                    primaryResponseLatch.countDown();
            }
        });
        Assert.assertTrue(pushLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(primaryResponseLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testPushIsReset() throws Exception
    {
        final String primaryResource = "/primary.html";
        final String secondaryResource = "/secondary.png";
        final byte[] secondaryData = "SECONDARY".getBytes("UTF-8");
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException
            {
                String requestURI = req.getRequestURI();
                ServletOutputStream output = resp.getOutputStream();
                if (requestURI.endsWith(primaryResource))
                    output.print("<html><head></head><body>PRIMARY</body></html>");
                else if (requestURI.endsWith(secondaryResource))
                    output.write(secondaryData);
            }
        });

        final Session session = newClient(new Session.Listener.Adapter());

        // Request for the primary and secondary resource to build the cache.
        final String primaryURI = "http://localhost:" + connector.getLocalPort() + servletPath + primaryResource;
        HttpFields primaryFields = new HttpFields();
        MetaData.Request primaryRequest = newRequest("GET", primaryResource, primaryFields);
        final CountDownLatch warmupLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(0, primaryRequest, null, true), new Promise.Adapter<Stream>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                {
                    // Request for the secondary resource.
                    HttpFields secondaryFields = new HttpFields();
                    secondaryFields.put(HttpHeader.REFERER, primaryURI);
                    MetaData.Request secondaryRequest = newRequest("GET", secondaryResource, secondaryFields);
                    session.newStream(new HeadersFrame(0, secondaryRequest, null, true), new Promise.Adapter<Stream>(), new Stream.Listener.Adapter()
                    {
                        @Override
                        public void onData(Stream stream, DataFrame frame, Callback callback)
                        {
                            warmupLatch.countDown();
                        }
                    });
                }
            }
        });
        Assert.assertTrue(warmupLatch.await(5, TimeUnit.SECONDS));

        // Request again the primary resource, we should get the secondary resource pushed.
        primaryRequest = newRequest("GET", primaryResource, primaryFields);
        final CountDownLatch primaryResponseLatch = new CountDownLatch(1);
        final CountDownLatch pushLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(0, primaryRequest, null, true), new Promise.Adapter<Stream>(), new Stream.Listener.Adapter()
        {
            @Override
            public Stream.Listener onPush(Stream stream, PushPromiseFrame frame)
            {
                // Reset the stream as soon as we see the push.
                ResetFrame resetFrame = new ResetFrame(stream.getId(), ErrorCode.REFUSED_STREAM_ERROR.code);
                stream.reset(resetFrame, Callback.Adapter.INSTANCE);
                return new Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        pushLatch.countDown();
                    }
                };
            }

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                if (frame.isEndStream())
                    primaryResponseLatch.countDown();
            }
        });
        // We should not receive pushed data that we reset.
        Assert.assertFalse(pushLatch.await(1, TimeUnit.SECONDS));
        Assert.assertTrue(primaryResponseLatch.await(5, TimeUnit.SECONDS));

        // Make sure the session is sane by requesting the secondary resource.
        HttpFields secondaryFields = new HttpFields();
        secondaryFields.put(HttpHeader.REFERER, primaryURI);
        MetaData.Request secondaryRequest = newRequest("GET", secondaryResource, secondaryFields);
        final CountDownLatch secondaryResponseLatch = new CountDownLatch(1);
        session.newStream(new HeadersFrame(0, secondaryRequest, null, true), new Promise.Adapter<Stream>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                if (frame.isEndStream())
                    secondaryResponseLatch.countDown();
            }
        });
        Assert.assertTrue(secondaryResponseLatch.await(5, TimeUnit.SECONDS));
    }
}
