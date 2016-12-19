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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.AsyncContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.ISession;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class StreamResetTest extends AbstractTest
{
    @Test
    public void testStreamSendingResetIsRemoved() throws Exception
    {
        start(new ServerSessionListener.Adapter());

        Session client = newClient(new Session.Listener.Adapter());
        MetaData.Request request = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(request, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        client.newStream(requestFrame, promise, new Stream.Listener.Adapter());
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        ResetFrame resetFrame = new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code);
        FutureCallback resetCallback = new FutureCallback();
        stream.reset(resetFrame, resetCallback);
        resetCallback.get(5, TimeUnit.SECONDS);
        // After reset the stream should be gone.
        Assert.assertEquals(0, client.getStreams().size());
    }

    @Test
    public void testStreamReceivingResetIsRemoved() throws Exception
    {
        final AtomicReference<Stream> streamRef = new AtomicReference<>();
        final CountDownLatch resetLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onReset(Stream stream, ResetFrame frame)
                    {
                        Assert.assertNotNull(stream);
                        Assert.assertTrue(stream.isReset());
                        streamRef.set(stream);
                        resetLatch.countDown();
                    }
                };
            }
        });

        Session client = newClient(new Session.Listener.Adapter());
        MetaData.Request request = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame = new HeadersFrame(request, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        client.newStream(requestFrame, promise, new Stream.Listener.Adapter());
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        ResetFrame resetFrame = new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code);
        stream.reset(resetFrame, Callback.NOOP);

        Assert.assertTrue(resetLatch.await(5, TimeUnit.SECONDS));

        // Wait a while to let the server remove the
        // stream after returning from onReset().
        Thread.sleep(1000);

        Stream serverStream = streamRef.get();
        Assert.assertEquals(0, serverStream.getSession().getStreams().size());
    }

    @Test
    public void testStreamResetDoesNotCloseConnection() throws Exception
    {
        final CountDownLatch serverResetLatch = new CountDownLatch(1);
        final CountDownLatch serverDataLatch = new CountDownLatch(1);
        start(new ServerSessionListener.Adapter()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, 200, new HttpFields());
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), response, null, false);
                Callback.Completable completable = new Callback.Completable();
                stream.headers(responseFrame, completable);
                return new Stream.Listener.Adapter()
                {
                    @Override
                    public void onData(Stream stream, DataFrame frame, Callback callback)
                    {
                        callback.succeeded();
                        completable.thenRun(() ->
                                stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(16), true), new Callback()
                                {
                                    @Override
                                    public void succeeded()
                                    {
                                        serverDataLatch.countDown();
                                    }
                                }));
                    }

                    @Override
                    public void onReset(Stream s, ResetFrame frame)
                    {
                        // Simulate that there is pending data to send.
                        IStream stream = (IStream)s;
                        stream.getSession().frames(stream, new Callback()
                        {
                            @Override
                            public void failed(Throwable x)
                            {
                                serverResetLatch.countDown();
                            }
                        }, new DataFrame(s.getId(), ByteBuffer.allocate(16), true));
                    }
                };
            }
        });

        Session client = newClient(new Session.Listener.Adapter());
        MetaData.Request request1 = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame1 = new HeadersFrame(request1, null, false);
        FuturePromise<Stream> promise1 = new FuturePromise<>();
        final CountDownLatch stream1HeadersLatch = new CountDownLatch(1);
        final CountDownLatch stream1DataLatch = new CountDownLatch(1);
        client.newStream(requestFrame1, promise1, new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                stream1HeadersLatch.countDown();
            }

            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                stream1DataLatch.countDown();
            }
        });
        Stream stream1 = promise1.get(5, TimeUnit.SECONDS);
        Assert.assertTrue(stream1HeadersLatch.await(5, TimeUnit.SECONDS));

        MetaData.Request request2 = newRequest("GET", new HttpFields());
        HeadersFrame requestFrame2 = new HeadersFrame(request2, null, false);
        FuturePromise<Stream> promise2 = new FuturePromise<>();
        final CountDownLatch stream2DataLatch = new CountDownLatch(1);
        client.newStream(requestFrame2, promise2, new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                callback.succeeded();
                stream2DataLatch.countDown();
            }
        });
        Stream stream2 = promise2.get(5, TimeUnit.SECONDS);

        ResetFrame resetFrame = new ResetFrame(stream1.getId(), ErrorCode.CANCEL_STREAM_ERROR.code);
        stream1.reset(resetFrame, Callback.NOOP);

        Assert.assertTrue(serverResetLatch.await(5, TimeUnit.SECONDS));
        // Stream MUST NOT receive data sent by server after reset.
        Assert.assertFalse(stream1DataLatch.await(1, TimeUnit.SECONDS));

        // The other stream should still be working.
        stream2.data(new DataFrame(stream2.getId(), ByteBuffer.allocate(16), true), Callback.NOOP);
        Assert.assertTrue(serverDataLatch.await(5, TimeUnit.SECONDS));
        Assert.assertTrue(stream2DataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testBlockingWriteAfterStreamReceivingReset() throws Exception
    {
        final CountDownLatch resetLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                Charset charset = StandardCharsets.UTF_8;
                byte[] data = "AFTER RESET".getBytes(charset);

                response.setStatus(200);
                response.setContentType("text/plain;charset=" + charset.name());
                response.setContentLength(data.length*10);
                response.flushBuffer();

                try
                {
                    // Wait for the reset to happen.
                    Assert.assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }

                try
                {
                    // Write some content after the stream has
                    // been reset, it should throw an exception.
                    for (int i=0;i<10;i++)
                    {
                        Thread.sleep(500);
                        response.getOutputStream().write(data);
                        response.flushBuffer();
                    }
                }
                catch (InterruptedException x)
                {

                }
                catch (IOException x)
                {
                    dataLatch.countDown();
                }
            }
        });

        Session client = newClient(new Session.Listener.Adapter());
        MetaData.Request request = newRequest("GET", new HttpFields());
        HeadersFrame frame = new HeadersFrame(request, null, true);
        client.newStream(frame, new FuturePromise<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
                resetLatch.countDown();
            }
        });

        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testAsyncWriteAfterStreamReceivingReset() throws Exception
    {
        final CountDownLatch resetLatch = new CountDownLatch(1);
        final CountDownLatch dataLatch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, final HttpServletResponse response) throws ServletException, IOException
            {
                Charset charset = StandardCharsets.UTF_8;
                final ByteBuffer data = ByteBuffer.wrap("AFTER RESET".getBytes(charset));

                response.setStatus(200);
                response.setContentType("text/plain;charset=" + charset.name());
                response.setContentLength(data.remaining());
                response.flushBuffer();

                try
                {
                    // Wait for the reset to happen.
                    Assert.assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
                    // Wait for the reset to arrive to the server and be processed.
                    Thread.sleep(1000);
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }

                // Write some content asynchronously after the stream has been reset.
                final AsyncContext context = request.startAsync();
                new Thread()
                {
                    @Override
                    public void run()
                    {
                        try
                        {
                            // Wait for the request thread to exit
                            // doGet() so this is really asynchronous.
                            Thread.sleep(1000);

                            HttpOutput output = (HttpOutput)response.getOutputStream();
                            output.sendContent(data, new Callback()
                            {
                                @Override
                                public void failed(Throwable x)
                                {
                                    context.complete();
                                    dataLatch.countDown();
                                }
                            });
                        }
                        catch (Throwable x)
                        {
                            x.printStackTrace();
                        }
                    }
                }.start();
            }
        });

        Session client = newClient(new Session.Listener.Adapter());
        MetaData.Request request = newRequest("GET", new HttpFields());
        HeadersFrame frame = new HeadersFrame(request, null, true);
        client.newStream(frame, new FuturePromise<>(), new Stream.Listener.Adapter()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
                resetLatch.countDown();
            }
        });

        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testClientResetConsumesQueuedData() throws Exception
    {
        start(new EmptyHttpServlet());

        Session client = newClient(new Session.Listener.Adapter());
        MetaData.Request request = newRequest("GET", new HttpFields());
        HeadersFrame frame = new HeadersFrame(request, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        client.newStream(frame, promise, new Stream.Listener.Adapter());
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        ByteBuffer data = ByteBuffer.allocate(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        CountDownLatch dataLatch = new CountDownLatch(1);
        stream.data(new DataFrame(stream.getId(), data, false), new Callback()
        {
            @Override
            public void succeeded()
            {
                dataLatch.countDown();
            }
        });
        // The server does not read the data, so the flow control window should be zero.
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
        Assert.assertEquals(0, ((ISession)client).updateSendWindow(0));

        // Now reset the stream.
        stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);

        // Wait for the server to receive the reset and process
        // it, and for the client to process the window updates.
        Thread.sleep(1000);

        Assert.assertThat(((ISession)client).updateSendWindow(0), Matchers.greaterThan(0));
    }

    @Test
    public void testServerExceptionConsumesQueuedData() throws Exception
    {
        try (StacklessLogging suppressor = new StacklessLogging(ServletHandler.class))
        {
            start(new HttpServlet()
            {
                @Override
                protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
                {
                    try
                    {
                        // Wait to let the data sent by the client to be queued.
                        Thread.sleep(1000);
                        throw new IllegalStateException("explictly_thrown_by_test");
                    }
                    catch (InterruptedException e)
                    {
                        throw new InterruptedIOException();
                    }
                }
            });

            Session client = newClient(new Session.Listener.Adapter());
            MetaData.Request request = newRequest("GET", new HttpFields());
            HeadersFrame frame = new HeadersFrame(request, null, false);
            FuturePromise<Stream> promise = new FuturePromise<>();
            client.newStream(frame, promise, new Stream.Listener.Adapter());
            Stream stream = promise.get(5, TimeUnit.SECONDS);
            ByteBuffer data = ByteBuffer.allocate(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
            CountDownLatch dataLatch = new CountDownLatch(1);
            stream.data(new DataFrame(stream.getId(), data, false), new Callback()
            {
                @Override
                public void succeeded()
                {
                    dataLatch.countDown();
                }
            });
            // The server does not read the data, so the flow control window should be zero.
            Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
            Assert.assertEquals(0, ((ISession)client).updateSendWindow(0));

            // Wait for the server process the exception, and
            // for the client to process the window updates.
            Thread.sleep(2000);

            Assert.assertThat(((ISession)client).updateSendWindow(0), Matchers.greaterThan(0));
        }
    }

    @Test
    public void testResetAfterAsyncRequestBlockingWriteStalledByFlowControl() throws Exception
    {
        int windowSize = FlowControlStrategy.DEFAULT_WINDOW_SIZE;
        CountDownLatch writeLatch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                AsyncContext asyncContext = request.startAsync();
                asyncContext.start(() ->
                {
                    try
                    {
                        // Make sure we are in async wait before writing.
                        Thread.sleep(1000);
                        response.getOutputStream().write(new byte[10 * windowSize]);
                        asyncContext.complete();
                    }
                    catch (IOException x)
                    {
                        writeLatch.countDown();
                    }
                    catch (Throwable x)
                    {
                        x.printStackTrace();
                    }
                });
            }
        });

        Deque<Object> dataQueue = new ArrayDeque<>();
        AtomicLong received = new AtomicLong();
        CountDownLatch latch = new CountDownLatch(1);
        Session client = newClient(new Session.Listener.Adapter());
        MetaData.Request request = newRequest("GET", new HttpFields());
        HeadersFrame frame = new HeadersFrame(request, null, true);
        FuturePromise<Stream> promise = new FuturePromise<>();
        client.newStream(frame, promise, new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                dataQueue.offer(frame);
                dataQueue.offer(callback);
                // Do not consume the data yet.
                if (received.addAndGet(frame.getData().remaining()) == windowSize)
                    latch.countDown();
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Reset and consume.
        stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
        dataQueue.stream()
                .filter(item -> item instanceof Callback)
                .map(item -> (Callback)item)
                .forEach(Callback::succeeded);

        Assert.assertTrue(writeLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testResetAfterAsyncRequestAsyncWriteStalledByFlowControl() throws Exception
    {
        int windowSize = FlowControlStrategy.DEFAULT_WINDOW_SIZE;
        CountDownLatch writeLatch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                AsyncContext asyncContext = request.startAsync();
                ServletOutputStream output = response.getOutputStream();
                output.setWriteListener(new WriteListener()
                {
                    private boolean written;

                    @Override
                    public void onWritePossible() throws IOException
                    {
                        while (output.isReady())
                        {
                            if (written)
                            {
                                asyncContext.complete();
                                break;
                            }
                            else
                            {
                                output.write(new byte[10 * windowSize]);
                                written = true;
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable t)
                    {
                        writeLatch.countDown();
                    }
                });
            }
        });

        Deque<Callback> dataQueue = new ArrayDeque<>();
        AtomicLong received = new AtomicLong();
        CountDownLatch latch = new CountDownLatch(1);
        Session client = newClient(new Session.Listener.Adapter());
        MetaData.Request request = newRequest("GET", new HttpFields());
        HeadersFrame frame = new HeadersFrame(request, null, true);
        FuturePromise<Stream> promise = new FuturePromise<>();
        client.newStream(frame, promise, new Stream.Listener.Adapter()
        {
            @Override
            public void onData(Stream stream, DataFrame frame, Callback callback)
            {
                dataQueue.offer(callback);
                // Do not consume the data yet.
                if (received.addAndGet(frame.getData().remaining()) == windowSize)
                    latch.countDown();
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Reset and consume.
        stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
        dataQueue.forEach(Callback::succeeded);

        Assert.assertTrue(writeLatch.await(5, TimeUnit.SECONDS));
    }
}
