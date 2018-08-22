//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.servlet.AsyncContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.HTTP2Flusher;
import org.eclipse.jetty.http2.HTTP2Session;
import org.eclipse.jetty.http2.ISession;
import org.eclipse.jetty.http2.IStream;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.server.AbstractHTTP2ServerConnectionFactory;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpOutput;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.StacklessLogging;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
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
        CountDownLatch commitLatch = new CountDownLatch(1);
        CountDownLatch resetLatch = new CountDownLatch(1);
        CountDownLatch dataLatch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                Charset charset = StandardCharsets.UTF_8;
                byte[] data = "AFTER RESET".getBytes(charset);

                response.setStatus(200);
                response.setContentType("text/plain;charset=" + charset.name());
                response.setContentLength(data.length * 10);
                response.flushBuffer();
                // Wait for the commit callback to complete.
                commitLatch.countDown();

                try
                {
                    // Wait for the reset to be sent.
                    Assert.assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
                    // Wait for the reset to arrive to the server and be processed.
                    Thread.sleep(1000);
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
                }

                try
                {
                    // Write some content after the stream has
                    // been reset, it should throw an exception.
                    for (int i = 0; i < 100; i++)
                    {
                        Thread.sleep(100);
                        response.getOutputStream().write(data);
                        response.flushBuffer();
                    }
                }
                catch (InterruptedException x)
                {
                    throw new InterruptedIOException();
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
                try
                {
                    commitLatch.await(5, TimeUnit.SECONDS);
                    Callback.Completable completable = new Callback.Completable();
                    stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), completable);
                    completable.thenRun(resetLatch::countDown);
                }
                catch (InterruptedException x)
                {
                    x.printStackTrace();
                }
            }
        });

        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testAsyncWriteAfterStreamReceivingReset() throws Exception
    {
        CountDownLatch commitLatch = new CountDownLatch(1);
        CountDownLatch resetLatch = new CountDownLatch(1);
        CountDownLatch dataLatch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void doGet(HttpServletRequest request, final HttpServletResponse response) throws IOException
            {
                Charset charset = StandardCharsets.UTF_8;
                final ByteBuffer data = ByteBuffer.wrap("AFTER RESET".getBytes(charset));

                response.setStatus(200);
                response.setContentType("text/plain;charset=" + charset.name());
                response.setContentLength(data.remaining());
                response.flushBuffer();
                // Wait for the commit callback to complete.
                commitLatch.countDown();

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
                new Thread(() ->
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
                }).start();
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
                try
                {
                    commitLatch.await(5, TimeUnit.SECONDS);
                    Callback.Completable completable = new Callback.Completable();
                    stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), completable);
                    completable.thenRun(resetLatch::countDown);
                }
                catch (InterruptedException x)
                {
                    x.printStackTrace();
                }
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
    public void testClientResetConsumesQueuedRequestWithData() throws Exception
    {
        // Use a small thread pool.
        QueuedThreadPool serverExecutor = new QueuedThreadPool(5);
        serverExecutor.setName("server");
        serverExecutor.setDetailedDump(true);
        server = new Server(serverExecutor);
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(new HttpConfiguration());
        h2.setInitialSessionRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        h2.setInitialStreamRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        connector = new ServerConnector(server, 1, 1, h2);
        server.addConnector(connector);
        ServletContextHandler context = new ServletContextHandler(server, "/");
        AtomicReference<CountDownLatch> phaser = new AtomicReference<>();
        context.addServlet(new ServletHolder(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                phaser.get().countDown();
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        }), servletPath + "/*");
        server.start();

        prepareClient();
        client.start();

        Session client = newClient(new Session.Listener.Adapter());

        // Send requests until one is queued on the server but not dispatched.
        AtomicReference<CountDownLatch> latch = new AtomicReference<>();
        List<Stream> streams = new ArrayList<>();
        while (true)
        {
            phaser.set(new CountDownLatch(1));

            MetaData.Request request = newRequest("GET", new HttpFields());
            HeadersFrame frame = new HeadersFrame(request, null, false);
            FuturePromise<Stream> promise = new FuturePromise<>();
            client.newStream(frame, promise, new Stream.Listener.Adapter()
            {
                @Override
                public void onHeaders(Stream stream, HeadersFrame frame)
                {
                    MetaData.Response response = (MetaData.Response)frame.getMetaData();
                    if (response.getStatus() == HttpStatus.OK_200)
                        latch.get().countDown();
                }

                @Override
                public void onData(Stream stream, DataFrame frame, Callback callback)
                {
                    callback.succeeded();
                    if (frame.isEndStream())
                        latch.get().countDown();
                }
            });
            Stream stream = promise.get(5, TimeUnit.SECONDS);
            streams.add(stream);
            ByteBuffer data = ByteBuffer.allocate(10);
            stream.data(new DataFrame(stream.getId(), data, false), Callback.NOOP);

            if (!phaser.get().await(1, TimeUnit.SECONDS))
                break;
        }

        // Send one more request to consume the whole session flow control window, then reset it.
        MetaData.Request request = newRequest("GET", "/x", new HttpFields());
        HeadersFrame frame = new HeadersFrame(request, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        // This request will get no event from the server since it's reset by the client.
        client.newStream(frame, promise, new Stream.Listener.Adapter());
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        ByteBuffer data = ByteBuffer.allocate(((ISession)client).updateSendWindow(0));
        stream.data(new DataFrame(stream.getId(), data, false), new Callback()
        {
            @Override
            public void succeeded()
            {
                stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), NOOP);
            }
        });

        // Wait for WINDOW_UPDATEs to be processed by the client.
        Thread.sleep(1000);

        Assert.assertThat(((ISession)client).updateSendWindow(0), Matchers.greaterThan(0));

        latch.set(new CountDownLatch(2 * streams.size()));
        // Complete all streams.
        streams.forEach(s -> s.data(new DataFrame(s.getId(), BufferUtil.EMPTY_BUFFER, true), Callback.NOOP));

        Assert.assertTrue(latch.get().await(5, TimeUnit.SECONDS));
    }

    @Test
    public void testServerExceptionConsumesQueuedData() throws Exception
    {
        try (StacklessLogging suppressor = new StacklessLogging(HttpChannel.class))
        {
            start(new HttpServlet()
            {
                @Override
                protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
                {
                    try
                    {
                        // Wait to let the data sent by the client to be queued.
                        Thread.sleep(1000);
                        throw new IllegalStateException("explicitly_thrown_by_test");
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
            protected void service(HttpServletRequest request, HttpServletResponse response)
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

    @Test
    public void testResetAfterBlockingWrite() throws Exception
    {
        int windowSize = FlowControlStrategy.DEFAULT_WINDOW_SIZE;
        CountDownLatch writeLatch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response)
            {
                try
                {
                    ServletOutputStream output = response.getOutputStream();
                    output.write(new byte[10 * windowSize]);
                }
                catch (IOException e)
                {
                    writeLatch.countDown();
                }
            }
        });

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
                if (received.addAndGet(frame.getData().remaining()) == windowSize)
                    latch.countDown();
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        Assert.assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Reset.
        stream.reset(new ResetFrame(stream.getId(), ErrorCode.CANCEL_STREAM_ERROR.code), Callback.NOOP);
        Assert.assertTrue(writeLatch.await(5, TimeUnit.SECONDS));

        // Give time to the server to process the reset and drain the flusher queue.
        Thread.sleep(500);

        AbstractHTTP2ServerConnectionFactory http2 = connector.getConnectionFactory(AbstractHTTP2ServerConnectionFactory.class);
        Set<Session> sessions = http2.getBean(AbstractHTTP2ServerConnectionFactory.HTTP2SessionContainer.class).getSessions();
        Assert.assertEquals(1, sessions.size());
        HTTP2Session session = (HTTP2Session)sessions.iterator().next();
        HTTP2Flusher flusher = session.getBean(HTTP2Flusher.class);
        Assert.assertEquals(0, flusher.getFrameQueueSize());
    }

    @Test
    public void testResetAfterAsyncRequestAsyncWriteStalledByFlowControl() throws Exception
    {
        int windowSize = FlowControlStrategy.DEFAULT_WINDOW_SIZE;
        CountDownLatch writeLatch = new CountDownLatch(1);
        start(new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
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
