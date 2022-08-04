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

package org.eclipse.jetty.http2.tests;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.FlowControlStrategy;
import org.eclipse.jetty.http2.api.Session;
import org.eclipse.jetty.http2.api.Stream;
import org.eclipse.jetty.http2.api.server.ServerSessionListener;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.GoAwayFrame;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.http2.internal.HTTP2Session;
import org.eclipse.jetty.http2.server.HTTP2ServerConnectionFactory;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FuturePromise;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class IdleTimeoutTest extends AbstractTest
{
    private final int idleTimeout = 1000;

    @Test
    public void testServerEnforcingIdleTimeout() throws Exception
    {
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame requestFrame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(responseFrame, Callback.NOOP);
                return null;
            }
        });
        connector.setIdleTimeout(idleTimeout);

        CountDownLatch latch = new CountDownLatch(1);
        Session session = newClientSession(new Session.Listener()
        {
            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                latch.countDown();
                callback.succeeded();
            }
        });

        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(10 * idleTimeout);
            }
        }, null);

        assertTrue(latch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testServerEnforcingIdleTimeoutWithUnrespondedStream() throws Exception
    {
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                return null;
            }
        });
        connector.setIdleTimeout(idleTimeout);

        CountDownLatch latch = new CountDownLatch(1);
        Session session = newClientSession(new Session.Listener()
        {
            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                latch.countDown();
                callback.succeeded();
            }
        });

        // The request is not replied, and the server should idle timeout.
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(10 * idleTimeout);
            }
        }, null);

        assertTrue(latch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testServerNotEnforcingIdleTimeoutWithinCallback() throws Exception
    {
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                // Stay in the callback for more than idleTimeout,
                // but not for an integer number of idle timeouts,
                // to avoid a race where the idle timeout fires
                // again before we can send the headers to the client.
                sleep(idleTimeout + idleTimeout / 2);
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(responseFrame, Callback.NOOP);
                return null;
            }
        });
        connector.setIdleTimeout(idleTimeout);

        CountDownLatch closeLatch = new CountDownLatch(1);
        Session session = newClientSession(new ServerSessionListener()
        {
            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                closeLatch.countDown();
                callback.succeeded();
            }
        });

        CountDownLatch replyLatch = new CountDownLatch(1);
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(10 * idleTimeout);
            }
        }, new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                replyLatch.countDown();
            }
        });

        assertTrue(replyLatch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));

        // Just make sure onClose() has never been called, but don't wait too much
        assertFalse(closeLatch.await(idleTimeout / 2, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testClientEnforcingIdleTimeout() throws Exception
    {
        CountDownLatch closeLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(responseFrame, Callback.NOOP);
                return null;
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                closeLatch.countDown();
                callback.succeeded();
            }
        });
        http2Client.setIdleTimeout(idleTimeout);

        Session session = newClientSession(new Session.Listener() {});
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(10 * idleTimeout);
            }
        }, null);

        assertTrue(closeLatch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));
        assertTrue(session.isClosed());
    }

    @Test
    public void testClientEnforcingIdleTimeoutWithUnrespondedStream() throws Exception
    {
        CountDownLatch closeLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                return null;
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                closeLatch.countDown();
                callback.succeeded();
            }
        });
        http2Client.setIdleTimeout(idleTimeout);

        Session session = newClientSession(new Session.Listener() {});
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(10 * idleTimeout);
            }
        }, null);

        assertTrue(closeLatch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testClientNotEnforcingIdleTimeoutWithinCallback() throws Exception
    {
        CountDownLatch closeLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(10 * idleTimeout);
                MetaData.Response metaData = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                HeadersFrame responseFrame = new HeadersFrame(stream.getId(), metaData, null, true);
                stream.headers(responseFrame, Callback.NOOP);
                return null;
            }

            @Override
            public void onClose(Session session, GoAwayFrame frame, Callback callback)
            {
                closeLatch.countDown();
                callback.succeeded();
            }
        });
        http2Client.setIdleTimeout(idleTimeout);

        Session session = newClientSession(new Session.Listener() {});

        CountDownLatch replyLatch = new CountDownLatch(1);
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(10 * idleTimeout);
            }
        }, new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                // Stay in the callback for more than idleTimeout,
                // but not for an integer number of idle timeouts,
                // to avoid that the idle timeout fires again.
                sleep(idleTimeout + idleTimeout / 2);
                replyLatch.countDown();
            }
        });

        assertFalse(closeLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        assertTrue(replyLatch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testClientEnforcingStreamIdleTimeout() throws Exception
    {
        int idleTimeout = 1000;
        AtomicReference<Throwable> thrownByCallback = new AtomicReference<>();
        start(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                sleep(2 * idleTimeout);

                try
                {
                    callback.succeeded();
                }
                catch (Throwable x)
                {
                    // Callback.succeeded() must not throw.
                    thrownByCallback.set(x);
                }

                try
                {
                    // Wrongly calling callback.failed() after succeeded() must not throw.
                    callback.failed(new Throwable("thrown by test"));
                }
                catch (Throwable x)
                {
                    // Callback.failed() must not throw.
                    thrownByCallback.set(x);
                }
            }
        });

        Session session = newClientSession(new Session.Listener() {});

        CountDownLatch dataLatch = new CountDownLatch(1);
        CountDownLatch timeoutLatch = new CountDownLatch(1);
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, true);
        session.newStream(requestFrame, new Promise.Adapter<>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(idleTimeout);
            }
        }, new Stream.Listener()
        {
            @Override
            public void onDataAvailable(Stream stream)
            {
                Stream.Data data = stream.readData();
                data.release();
                dataLatch.countDown();
            }

            @Override
            public boolean onIdleTimeout(Stream stream, Throwable x)
            {
                assertThat(x, Matchers.instanceOf(TimeoutException.class));
                timeoutLatch.countDown();
                return true;
            }
        });

        assertTrue(timeoutLatch.await(5, TimeUnit.SECONDS));
        // We must not receive any DATA frame.
        // This is because while the server receives a reset, there is a thread
        // dispatched to the application, which could (but in this test does not)
        // read from the request to notice the reset, so the server processing
        // completes successfully with 200 and no DATA, rather than a 500 with DATA.
        assertFalse(dataLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));
        // No exceptions thrown by the callback on server.
        assertNull(thrownByCallback.get());
        // Stream must be gone.
        assertTrue(session.getStreams().isEmpty());
        // Session must not be closed, nor disconnected.
        assertFalse(session.isClosed());
        assertFalse(((HTTP2Session)session).isDisconnected());
    }

    @Test
    public void testServerEnforcingStreamIdleTimeout() throws Exception
    {
        CountDownLatch timeoutLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(idleTimeout);
                return new Stream.Listener()
                {
                    @Override
                    public boolean onIdleTimeout(Stream stream, Throwable x)
                    {
                        timeoutLatch.countDown();
                        return true;
                    }
                };
            }
        });

        CountDownLatch resetLatch = new CountDownLatch(1);
        Session session = newClientSession(new Session.Listener() {});
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        // Stream does not end here, but we won't send any DATA frame.
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, false);
        session.newStream(requestFrame, new Promise.Adapter<>(), new Stream.Listener()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame, Callback callback)
            {
                resetLatch.countDown();
                callback.succeeded();
            }
        });

        assertTrue(timeoutLatch.await(5, TimeUnit.SECONDS));
        assertTrue(resetLatch.await(5, TimeUnit.SECONDS));
        // Stream must be gone.
        assertTrue(session.getStreams().isEmpty());
        // Session must not be closed, nor disconnected.
        assertFalse(session.isClosed());
        assertFalse(((HTTP2Session)session).isDisconnected());
    }

    @Test
    public void testServerStreamIdleTimeoutIsNotEnforcedWhenReceiving() throws Exception
    {
        CountDownLatch timeoutLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                stream.setIdleTimeout(idleTimeout);
                stream.demand();
                return new Stream.Listener()
                {
                    @Override
                    public boolean onIdleTimeout(Stream stream, Throwable x)
                    {
                        timeoutLatch.countDown();
                        return true;
                    }
                };
            }
        });

        Session session = newClientSession(new Session.Listener() {});
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        session.newStream(requestFrame, promise, null);
        Stream stream = promise.get(5, TimeUnit.SECONDS);

        sleep(idleTimeout / 2);
        CountDownLatch dataLatch = new CountDownLatch(1);
        stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(1), false), new Callback()
        {
            private int sends;

            @Override
            public void succeeded()
            {
                sleep(idleTimeout / 2);
                boolean last = ++sends == 2;
                stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(1), last), !last ? this : new Callback()
                {
                    @Override
                    public void succeeded()
                    {
                        // Idle timeout should not fire while the server is receiving.
                        assertEquals(1, timeoutLatch.getCount());
                        dataLatch.countDown();
                    }

                    @Override
                    public InvocationType getInvocationType()
                    {
                        return InvocationType.NON_BLOCKING;
                    }
                });
            }
        });

        assertTrue(dataLatch.await(5 * idleTimeout, TimeUnit.MILLISECONDS));
        // The server did not send a response, so it will eventually timeout.
        assertTrue(timeoutLatch.await(5 * idleTimeout, TimeUnit.SECONDS));
    }

    @Test
    public void testClientStreamIdleTimeoutIsNotEnforcedWhenSending() throws Exception
    {
        CountDownLatch resetLatch = new CountDownLatch(1);
        start(new ServerSessionListener()
        {
            @Override
            public Stream.Listener onNewStream(Stream stream, HeadersFrame frame)
            {
                MetaData.Response response = new MetaData.Response(HttpVersion.HTTP_2, 200, HttpFields.EMPTY);
                stream.headers(new HeadersFrame(stream.getId(), response, null, true), Callback.NOOP);
                return null;
            }

            @Override
            public void onReset(Session session, ResetFrame frame)
            {
                resetLatch.countDown();
            }
        });

        Session session = newClientSession(new Session.Listener() {});
        MetaData.Request metaData = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>()
        {
            @Override
            public void succeeded(Stream stream)
            {
                stream.setIdleTimeout(idleTimeout);
                super.succeeded(stream);
            }
        };
        session.newStream(requestFrame, promise, null);
        Stream stream = promise.get(5, TimeUnit.SECONDS);

        Callback.Completable completable1 = new Callback.Completable();
        sleep(idleTimeout / 2);
        stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(1), false), completable1);
        completable1.thenCompose(nil ->
        {
            Callback.Completable completable2 = new Callback.Completable();
            sleep(idleTimeout / 2);
            stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(1), false), completable2);
            return completable2;
        }).thenRun(() ->
        {
            sleep(idleTimeout / 2);
            stream.data(new DataFrame(stream.getId(), ByteBuffer.allocate(1), true), Callback.NOOP);
        });

        assertFalse(resetLatch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testBufferedReadsResetStreamIdleTimeout() throws Exception
    {
        int bufferSize = 8192;
        long delay = 1000;
        start(new Handler.Processor()
        {
            private Request _request;
            private Callback _callback;

            @Override
            public void process(Request request, Response response, Callback callback)
            {
                _request = request;
                _callback = callback;
                request.demand(this::onContentAvailable);
            }

            private void onContentAvailable()
            {
                while (true)
                {
                    Content.Chunk chunk = _request.read();
                    if (chunk == null)
                    {
                        _request.demand(this::onContentAvailable);
                        return;
                    }
                    if (chunk instanceof Content.Chunk.Error error)
                    {
                        _callback.failed(error.getCause());
                        return;
                    }
                    chunk.release();
                    if (chunk.isLast())
                    {
                        _callback.succeeded();
                        return;
                    }
                    sleep(delay);
                }
            }
        });
        // The timeout is going to be reset each time a DATA frame is fully consumed, hence
        // every 2 loops in the above servlet. So the IdleTimeout must be greater than (2 * delay)
        // to make sure it does not fire spuriously.
        connector.setIdleTimeout(3 * delay);

        Session session = newClientSession(new Session.Listener() {});
        MetaData.Request metaData = newRequest("POST", HttpFields.EMPTY);
        HeadersFrame requestFrame = new HeadersFrame(metaData, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        CountDownLatch latch = new CountDownLatch(1);
        session.newStream(requestFrame, promise, new Stream.Listener()
        {
            @Override
            public void onHeaders(Stream stream, HeadersFrame frame)
            {
                if (frame.isEndStream())
                    latch.countDown();
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);

        // Send data larger than the flow control window.
        // The client will send bytes up to the flow control window immediately
        // and they will be buffered by the server; the Servlet will consume them slowly.
        // Servlet reads should reset the idle timeout.
        int contentLength = FlowControlStrategy.DEFAULT_WINDOW_SIZE + 1;
        ByteBuffer data = ByteBuffer.allocate(contentLength);
        stream.data(new DataFrame(stream.getId(), data, true), Callback.NOOP);

        assertTrue(latch.await(2 * (contentLength / bufferSize + 1) * delay, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testServerIdleTimeoutIsEnforcedForQueuedRequest() throws Exception
    {
        long idleTimeout = 2000;
        // Use a small thread pool to cause request queueing.
        QueuedThreadPool serverExecutor = new QueuedThreadPool(5);
        serverExecutor.setName("server");
        server = new Server(serverExecutor);
        HTTP2ServerConnectionFactory h2 = new HTTP2ServerConnectionFactory(new HttpConfiguration());
        h2.setInitialSessionRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        h2.setInitialStreamRecvWindow(FlowControlStrategy.DEFAULT_WINDOW_SIZE);
        h2.setStreamIdleTimeout(idleTimeout);
        connector = new ServerConnector(server, 1, 1, h2);
        connector.setIdleTimeout(10 * idleTimeout);
        server.addConnector(connector);
        AtomicReference<CountDownLatch> phaser = new AtomicReference<>();
        server.setHandler(new Handler.Processor()
        {
            @Override
            public void process(Request request, Response response, Callback callback)
            {
                System.err.println("processing request " + request.getHttpURI().getPath());
                phaser.get().countDown();
                // Hold the dispatched requests enough for the idle requests to idle timeout.
                sleep(2 * idleTimeout);
                callback.succeeded();
            }
        });
        server.start();

        prepareClient();
        http2Client.start();

        Session client = newClientSession(new Session.Listener() {});

        // Send requests until one is queued on the server but not dispatched.
        int count = 0;
        while (true)
        {
            ++count;
            phaser.set(new CountDownLatch(1));

            MetaData.Request request = newRequest("GET", "/" + count, HttpFields.EMPTY);
            HeadersFrame frame = new HeadersFrame(request, null, false);
            FuturePromise<Stream> promise = new FuturePromise<>();
            client.newStream(frame, promise, null);
            Stream stream = promise.get(5, TimeUnit.SECONDS);
            ByteBuffer data = ByteBuffer.allocate(10);
            stream.data(new DataFrame(stream.getId(), data, true), Callback.NOOP);

            if (!phaser.get().await(1, TimeUnit.SECONDS))
                break;
        }

        // Send one more request to consume the whole session flow control window.
        CountDownLatch resetLatch = new CountDownLatch(1);
        MetaData.Request request = newRequest("GET", HttpFields.EMPTY);
        HeadersFrame frame = new HeadersFrame(request, null, false);
        FuturePromise<Stream> promise = new FuturePromise<>();
        client.newStream(frame, promise, new Stream.Listener()
        {
            @Override
            public void onReset(Stream stream, ResetFrame frame, Callback callback)
            {
                callback.succeeded();
                resetLatch.countDown();
            }
        });
        Stream stream = promise.get(5, TimeUnit.SECONDS);
        ByteBuffer data = ByteBuffer.allocate(((HTTP2Session)client).updateSendWindow(0));
        stream.data(new DataFrame(stream.getId(), data, true), Callback.NOOP);

        assertTrue(resetLatch.await(2 * idleTimeout, TimeUnit.MILLISECONDS));

        // Wait for WINDOW_UPDATEs to be processed by the client.
        sleep(1000);

        assertThat(((HTTP2Session)client).updateSendWindow(0), Matchers.greaterThan(0));
    }

    private void sleep(long value)
    {
        try
        {
            TimeUnit.MILLISECONDS.sleep(value);
        }
        catch (InterruptedException x)
        {
            fail(x);
        }
    }
}
