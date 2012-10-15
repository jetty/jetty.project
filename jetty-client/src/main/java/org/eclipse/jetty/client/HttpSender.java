//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpGenerator;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpSender
{
    private static final Logger LOG = Log.getLogger(HttpSender.class);
    private static final String EXPECT_100_ATTRIBUTE = HttpSender.class.getName() + ".expect100";

    private final HttpGenerator generator = new HttpGenerator();
    private final HttpConnection connection;
    private final RequestNotifier requestNotifier;
    private final ResponseNotifier responseNotifier;
    private Iterator<ByteBuffer> contentIterator;
    private ContentInfo expectedContent;
    private boolean committed;
    private boolean failed;

    public HttpSender(HttpConnection connection)
    {
        this.connection = connection;
        this.requestNotifier = new RequestNotifier(connection.getHttpClient());
        this.responseNotifier = new ResponseNotifier(connection.getHttpClient());
    }

    public void send(HttpExchange exchange)
    {
        Request request = exchange.request();
        if (request.aborted())
        {
            fail(new HttpRequestException("Request aborted", request));
        }
        else
        {
            LOG.debug("Sending {}", request);
            requestNotifier.notifyBegin(request);
            ContentProvider content = request.content();
            this.contentIterator = content == null ? Collections.<ByteBuffer>emptyIterator() : content.iterator();
            send();
        }
    }

    public void proceed(boolean proceed)
    {
        ContentInfo contentInfo = expectedContent;
        if (contentInfo != null)
        {
            contentInfo.await();
            if (proceed)
                send();
            else
                fail(new HttpRequestException("Expectation failed", connection.getExchange().request()));
        }
    }

    private void send()
    {
        HttpClient client = connection.getHttpClient();
        ByteBufferPool bufferPool = client.getByteBufferPool();
        ByteBuffer header = null;
        ByteBuffer chunk = null;
        try
        {
            EndPoint endPoint = connection.getEndPoint();
            HttpExchange exchange = connection.getExchange();
            final Request request = exchange.request();
            HttpConversation conversation = client.getConversation(request.conversation());
            HttpGenerator.RequestInfo requestInfo = null;

            boolean expect100 = request.headers().contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());
            expect100 &= conversation.getAttribute(EXPECT_100_ATTRIBUTE) == null;
            if (expect100)
                conversation.setAttribute(EXPECT_100_ATTRIBUTE, Boolean.TRUE);

            ContentInfo contentInfo = this.expectedContent;
            if (contentInfo == null)
                contentInfo = new ContentInfo(contentIterator);
            else
                expect100 = false;
            this.expectedContent = null;

            while (true)
            {
                HttpGenerator.Result result = generator.generateRequest(requestInfo, header, chunk, contentInfo.content, contentInfo.lastContent);
                switch (result)
                {
                    case NEED_INFO:
                    {
                        ContentProvider content = request.content();
                        long contentLength = content == null ? -1 : content.length();
                        requestInfo = new HttpGenerator.RequestInfo(request.version(), request.headers(), contentLength, request.method().asString(), request.path());
                        break;
                    }
                    case NEED_HEADER:
                    {
                        header = bufferPool.acquire(client.getRequestBufferSize(), false);
                        break;
                    }
                    case NEED_CHUNK:
                    {
                        chunk = bufferPool.acquire(HttpGenerator.CHUNK_SIZE, false);
                        break;
                    }
                    case FLUSH:
                    {
                        if (request.aborted())
                        {
                            fail(new HttpRequestException("Request aborted", request));
                        }
                        else
                        {
                            StatefulExecutorCallback callback = new StatefulExecutorCallback(client.getExecutor())
                            {
                                @Override
                                protected void pendingCompleted()
                                {
                                    LOG.debug("Write completed for {}", request);

                                    if (!committed)
                                        committed(request);

                                    if (expectedContent == null)
                                    {
                                        send();
                                    }
                                    else
                                    {
                                        LOG.debug("Expecting 100 Continue for {}", request);
                                        expectedContent.ready();
                                    }
                                }

                                @Override
                                protected void failed(Throwable x)
                                {
                                    fail(x);
                                }
                            };

                            if (expect100)
                            {
                                // Save the expected content waiting for the 100 Continue response
                                expectedContent = contentInfo;
                            }

                            write(callback, header, chunk, expect100 ? null : contentInfo.content);

                            if (callback.pending())
                            {
                                LOG.debug("Write pending for {}", request);
                                return;
                            }

                            if (callback.completed())
                            {
                                if (!committed)
                                    committed(request);

                                if (expect100)
                                {
                                    LOG.debug("Expecting 100 Continue for {}", request);
                                    expectedContent.ready();
                                    return;
                                }
                                else
                                {
                                    // Send further content
                                    contentInfo = new ContentInfo(contentIterator);
                                }
                            }
                        }
                        break;
                    }
                    case SHUTDOWN_OUT:
                    {
                        endPoint.shutdownOutput();
                        break;
                    }
                    case CONTINUE:
                    {
                        break;
                    }
                    case DONE:
                    {
                        if (generator.isEnd() && !failed)
                            success();
                        return;
                    }
                    default:
                    {
                        throw new IllegalStateException("Unknown result " + result);
                    }
                }
            }
        }
        catch (Exception x)
        {
            LOG.debug(x);
            fail(x);
        }
        finally
        {
            releaseBuffers(bufferPool, header, chunk);
        }
    }

    private void write(Callback<Void> callback, ByteBuffer header, ByteBuffer chunk, ByteBuffer content)
    {
        int mask = 0;
        if (header != null)
            mask += 1;
        if (chunk != null)
            mask += 2;
        if (content != null)
            mask += 4;

        EndPoint endPoint = connection.getEndPoint();
        switch (mask)
        {
            case 0:
                endPoint.write(null, callback, BufferUtil.EMPTY_BUFFER);
                break;
            case 1:
                endPoint.write(null, callback, header);
                break;
            case 2:
                endPoint.write(null, callback, chunk);
                break;
            case 3:
                endPoint.write(null, callback, header, chunk);
                break;
            case 4:
                endPoint.write(null, callback, content);
                break;
            case 5:
                endPoint.write(null, callback, header, content);
                break;
            case 6:
                endPoint.write(null, callback, chunk, content);
                break;
            case 7:
                endPoint.write(null, callback, header, chunk, content);
                break;
            default:
                throw new IllegalStateException();
        }
    }

    protected void committed(Request request)
    {
        LOG.debug("Committed {}", request);
        committed = true;
        requestNotifier.notifyHeaders(request);
    }

    protected void success()
    {
        // Cleanup first
        generator.reset();
        committed = false;

        // Notify after
        HttpExchange exchange = connection.getExchange();
        Request request = exchange.request();
        LOG.debug("Sent {}", request);

        Result result = exchange.requestComplete(null);

        // It is important to notify *after* we reset because
        // the notification may trigger another request/response
        requestNotifier.notifySuccess(request);
        if (result != null)
        {
            HttpConversation conversation = exchange.conversation();
            responseNotifier.notifyComplete(conversation.listener(), result);
        }
    }

    protected void fail(Throwable failure)
    {
        // Cleanup first
        generator.abort();
        failed = true;

        // Notify after
        HttpExchange exchange = connection.getExchange();
        Request request = exchange.request();
        LOG.debug("Failed {} {}", request, failure);

        Result result = exchange.requestComplete(failure);
        if (result == null && !committed)
            result = exchange.responseComplete(null);

        // If the exchange is not completed, we need to shutdown the output
        // to signal to the server that we're done (otherwise it may be
        // waiting for more data that will not arrive)
        if (result == null)
            connection.getEndPoint().shutdownOutput();

        requestNotifier.notifyFailure(request, failure);
        if (result != null)
        {
            HttpConversation conversation = exchange.conversation();
            responseNotifier.notifyComplete(conversation.listener(), result);
        }
    }

    private void releaseBuffers(ByteBufferPool bufferPool, ByteBuffer header, ByteBuffer chunk)
    {
        if (!BufferUtil.hasContent(header))
            bufferPool.release(header);
        if (!BufferUtil.hasContent(chunk))
            bufferPool.release(chunk);
    }

    private static abstract class StatefulExecutorCallback implements Callback<Void>, Runnable
    {
        private final AtomicReference<State> state = new AtomicReference<>(State.INCOMPLETE);
        private final Executor executor;

        private StatefulExecutorCallback(Executor executor)
        {
            this.executor = executor;
        }

        @Override
        public final void completed(final Void context)
        {
            State previous = state.get();
            while (true)
            {
                if (state.compareAndSet(previous, State.COMPLETE))
                    break;
                previous = state.get();
            }
            if (previous == State.PENDING)
                executor.execute(this);
        }

        @Override
        public final void run()
        {
            pendingCompleted();
        }

        protected abstract void pendingCompleted();

        @Override
        public final void failed(Void context, final Throwable x)
        {
            State previous = state.get();
            while (true)
            {
                if (state.compareAndSet(previous, State.FAILED))
                    break;
                previous = state.get();
            }
            if (previous == State.PENDING)
            {
                executor.execute(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        failed(x);
                    }
                });
            }
            else
            {
                failed(x);
            }
        }

        protected abstract void failed(Throwable x);

        public boolean pending()
        {
            return state.compareAndSet(State.INCOMPLETE, State.PENDING);
        }

        public boolean completed()
        {
            return state.get() == State.COMPLETE;
        }

        public boolean failed()
        {
            return state.get() == State.FAILED;
        }

        private enum State
        {
            INCOMPLETE, PENDING, COMPLETE, FAILED
        }
    }

    private class ContentInfo
    {
        private final CountDownLatch latch = new CountDownLatch(1);
        public final boolean lastContent;
        public final ByteBuffer content;

        public ContentInfo(Iterator<ByteBuffer> contentIterator)
        {
            lastContent = !contentIterator.hasNext();
            content = lastContent ? BufferUtil.EMPTY_BUFFER : contentIterator.next();
        }

        public void ready()
        {
            latch.countDown();
        }

        public void await()
        {
            try
            {
                latch.await();
            }
            catch (InterruptedException x)
            {
                throw new IllegalStateException(x);
            }
        }
    }
}
