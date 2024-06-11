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

package org.eclipse.jetty.http2.tests;

import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class AsyncIOTest extends AbstractTest
{
    @Test
    public void testLastContentAvailableBeforeService() throws Exception
    {
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                // Wait for the data to fully arrive.
                sleep(1000);
                Content.Source.consumeAll(request);
                callback.succeeded();
                return true;
            }
        });

        Session session = newClientSession(new Session.Listener() {});

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, false);
        final CountDownLatch latch = new CountDownLatch(1);
        FuturePromise<Stream> promise = new FuturePromise<>();
        session.newStream(frame, promise, new Stream.Listener()
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
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                Content.Source.consumeAll(request);
                callback.succeeded();
                return true;
            }
        });

        Session session = newClientSession(new Session.Listener() {});

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, false);
        final CountDownLatch latch = new CountDownLatch(1);
        FuturePromise<Stream> promise = new FuturePromise<>();
        session.newStream(frame, promise, new Stream.Listener()
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
    @Disabled("port to ee9")
    public void testSomeContentAvailableAfterServiceReturns()
    {
        fail();
/*
        final AtomicInteger count = new AtomicInteger();
        start(new Handler.Abstract()
        {
            @Override
            public void handle(request request, Response response, Callback callback) throws Exception
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

        Session session = newClient(new Session.Listener() {});

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, false);
        final CountDownLatch latch = new CountDownLatch(1);
        FuturePromise<Stream> promise = new FuturePromise<>();
        session.newStream(frame, promise, new Stream.Listener()
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
*/
    }

    @Test
    @Disabled("port to ee9")
    public void testDirectAsyncWriteThenComplete()
    {
        fail();
/*
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

        Session session = newClient(new Session.Listener()
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
        session.newStream(frame, promise, new Stream.Listener()
        {
            @Override
            public void onClosed(Stream stream)
            {
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
*/
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testClientResetWithoutRemoteErrorNotification(boolean notify) throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Response> responseRef = new AtomicReference<>();
        AtomicReference<Throwable> failureRef = new AtomicReference<>();
        HttpConfiguration httpConfiguration = new HttpConfiguration();
        httpConfiguration.setNotifyRemoteAsyncErrors(notify);
        start(new Handler.Abstract() {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                request.addFailureListener(failureRef::set);
                responseRef.set(response);
                latch.countDown();
                return true;
            }
        }, httpConfiguration);

        Session session = newClientSession(new Session.Listener() {});
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(metaData, null, true);
        FuturePromise<Stream> promise = new FuturePromise<>();
        session.newStream(frame, promise, null);
        Stream stream = promise.get(5, TimeUnit.SECONDS);

        // Wait for the server to be idle.
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        sleep(500);

        stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);

        if (notify)
            // Wait for the reset to be notified to the failure listener.
            await().atMost(5, TimeUnit.SECONDS).until(failureRef::get, instanceOf(EofException.class));
        else
            // Wait for the reset to NOT be notified to the failure listener.
            await().atMost(5, TimeUnit.SECONDS).during(1, TimeUnit.SECONDS).until(failureRef::get, nullValue());

        // Assert that writing to the response fails.
        var cb = new Callback()
        {
            private Throwable failure = null;

            @Override
            public void failed(Throwable x)
            {
                failure = x;
            }

            Throwable failure()
            {
                return failure;
            }
        };
        responseRef.get().write(true, BufferUtil.EMPTY_BUFFER, cb);
        await().atMost(5, TimeUnit.SECONDS).until(cb::failure, instanceOf(EofException.class));
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
}
