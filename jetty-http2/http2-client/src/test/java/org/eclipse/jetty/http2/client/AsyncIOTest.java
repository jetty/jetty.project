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

package org.eclipse.jetty.http2.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
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
import org.eclipse.jetty.util.FuturePromise;
import org.junit.Assert;
import org.junit.Test;

public class AsyncIOTest extends AbstractTest
{
    @Test
    public void testLastContentAvailableBeforeService() throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void service(final HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                // Wait for the data to fully arrive.
                sleep(1000);

                final AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                request.getInputStream().setReadListener(new EmptyReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        ServletInputStream input = request.getInputStream();
                        while (input.isReady())
                        {
                            int read = input.read();
                            if (read < 0)
                                break;
                        }
                        if (input.isFinished())
                            asyncContext.complete();
                    }
                });
            }
        });

        Session session = newClient(new Session.Listener.Adapter());

        HttpFields fields = new HttpFields();
        MetaData.Request metaData = newRequest("GET", fields);
        HeadersFrame frame = new HeadersFrame(metaData, null, false);
        final CountDownLatch latch = new CountDownLatch(1);
        FuturePromise<Stream> promise = new FuturePromise<>();
        session.newStream(frame, promise, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    latch.countDown();
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(16), true), Callback.NOOP);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testLastContentAvailableAfterServiceReturns() throws Exception
    {
        start(new HttpServlet()
        {
            @Override
            protected void service(final HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                final AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                request.getInputStream().setReadListener(new EmptyReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        ServletInputStream input = request.getInputStream();
                        while (input.isReady())
                        {
                            int read = input.read();
                            if (read < 0)
                                break;
                        }
                        if (input.isFinished())
                            asyncContext.complete();
                    }
                });
            }
        });

        Session session = newClient(new Session.Listener.Adapter());

        HttpFields fields = new HttpFields();
        MetaData.Request metaData = newRequest("GET", fields);
        HeadersFrame frame = new HeadersFrame(metaData, null, false);
        final CountDownLatch latch = new CountDownLatch(1);
        FuturePromise<Stream> promise = new FuturePromise<>();
        session.newStream(frame, promise, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    latch.countDown();
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);

        // Wait until service() returns.
        Thread.sleep(1000);
        stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(16), true), Callback.NOOP);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testSomeContentAvailableAfterServiceReturns() throws Exception
    {
        final AtomicInteger count = new AtomicInteger();
        start(new HttpServlet()
        {
            @Override
            protected void service(final HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                final AsyncContext asyncContext = request.startAsync();
                asyncContext.setTimeout(0);
                request.getInputStream().setReadListener(new EmptyReadListener()
                {
                    @Override
                    public void onDataAvailable() throws IOException
                    {
                        count.incrementAndGet();
                        ServletInputStream input = request.getInputStream();
                        while (input.isReady())
                        {
                            int read = input.read();
                            if (read < 0)
                                break;
                        }
                        if (input.isFinished())
                            asyncContext.complete();
                    }
                });
            }
        });

        Session session = newClient(new Session.Listener.Adapter());

        HttpFields fields = new HttpFields();
        MetaData.Request metaData = newRequest("GET", fields);
        HeadersFrame frame = new HeadersFrame(metaData, null, false);
        final CountDownLatch latch = new CountDownLatch(1);
        FuturePromise<Stream> promise = new FuturePromise<>();
        session.newStream(frame, promise, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    latch.countDown();
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);

        // Wait until service() returns.
        Thread.sleep(1000);
        stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(1), false), Callback.NOOP);

        // Wait until onDataAvailable() returns.
        Thread.sleep(1000);
        stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(1), true), Callback.NOOP);

        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));
        // Make sure onDataAvailable() has been called twice
        Assert.assertEquals(2, count.get());
    }

    private static void sleep(long ms) throws InterruptedIOException
    {
        try
        {
            Thread.sleep(ms);
        }
        catch (InterruptedException x)
        {
            throw new InterruptedIOException();
        }
    }

    private static class EmptyReadListener implements ReadListener
    {
        @Override
        public void onDataAvailable() throws IOException
        {
        }

        @Override
        public void onAllDataRead() throws IOException
        {
        }

        @Override
        public void onError(Throwable t)
        {
        }
    }
}
