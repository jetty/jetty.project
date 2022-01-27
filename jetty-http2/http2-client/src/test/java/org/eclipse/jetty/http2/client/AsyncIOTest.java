//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.client;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
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

        assertTrue(latch.await(5, TimeUnit.SECONDS));
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

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
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

        assertTrue(latch.await(5, TimeUnit.SECONDS));
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

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
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

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        // Make sure onDataAvailable() has been called twice
        assertEquals(2, count.get());
    }

    @Test
    public void testDirectAsyncWriteThenComplete() throws Exception
    {
        // Use a small flow control window to stall the server writes.
        int clientWindow = 16;
        start(new EmptyHttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                AsyncContext asyncContext = request.startAsync();
                HttpOutput output = (HttpOutput)response.getOutputStream();
                output.setWriteListener(new WriteListener()
                {
                    @Override
                    public void onWritePossible() throws IOException
                    {
                        // The write is too large and will stall.
                        output.write(ByteBuffer.wrap(new byte[2 * clientWindow]));

                        // We can now call complete() now before checking for isReady().
                        // This will asynchronously complete when the write is finished.
                        asyncContext.complete();
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                    }
                });
            }
        });

        Session session = newClient(new Session.Listener.Adapter()
        {
            @Override
            public Map<Integer, Integer> onPreface(Session session)
            {
                Map<Integer, Integer> settings = new HashMap<>();
                settings.put(SettingsFrame.INITIAL_WINDOW_SIZE, clientWindow);
                return settings;
            }
        });

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        CountDownLatch latch = new CountDownLatch(1);
        FuturePromise<Stream> promise = new FuturePromise<>();
        session.newStream(frame, promise, new Stream.Listener.Adapter()
        {
            @Override
            public void onClosed(Stream stream)
            {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
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
