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
import java.util.concurrent.atomic.AtomicMarkableReference;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
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

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final HttpGenerator generator = new HttpGenerator();
    private final HttpConnection connection;
    private final RequestNotifier requestNotifier;
    private final ResponseNotifier responseNotifier;
    private Iterator<ByteBuffer> contentIterator;
    private ContentInfo expectedContent;

    public HttpSender(HttpConnection connection)
    {
        this.connection = connection;
        this.requestNotifier = new RequestNotifier(connection.getHttpClient());
        this.responseNotifier = new ResponseNotifier(connection.getHttpClient());
    }

    public void send(HttpExchange exchange)
    {
        if (!updateState(State.IDLE, State.SEND))
            throw new IllegalStateException();

        // Arrange the listeners, so that if there is a request failure the proper listeners are notified
        HttpConversation conversation = exchange.getConversation();
        Response.Listener currentListener = exchange.getResponseListener();
        Response.Listener initialListener = conversation.getExchanges().peekFirst().getResponseListener();
        if (initialListener == currentListener)
            conversation.setResponseListener(initialListener);
        else
            conversation.setResponseListener(new DoubleResponseListener(responseNotifier, currentListener, initialListener));

        Request request = exchange.getRequest();
        if (request.aborted())
        {
            exchange.abort(null);
        }
        else
        {
            LOG.debug("Sending {}", request);
            requestNotifier.notifyBegin(request);
            ContentProvider content = request.getContent();
            this.contentIterator = content == null ? Collections.<ByteBuffer>emptyIterator() : content.iterator();
            send();
        }
    }

    public void proceed(boolean proceed)
    {
        ContentInfo contentInfo = expectedContent;
        if (contentInfo != null)
        {
            if (proceed)
            {
                LOG.debug("Proceeding {}", connection.getExchange());
                contentInfo.await();
                send();
            }
            else
            {
                HttpExchange exchange = connection.getExchange();
                if (exchange != null)
                    fail(new HttpRequestException("Expectation failed", exchange.getRequest()));
            }
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
            HttpExchange exchange = connection.getExchange();
            // The exchange may be null if it failed concurrently
            if (exchange == null)
                return;

            final Request request = exchange.getRequest();
            HttpConversation conversation = exchange.getConversation();
            HttpGenerator.RequestInfo requestInfo = null;

            boolean expect100 = request.getHeaders().contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());
            expect100 &= conversation.getAttribute(EXPECT_100_ATTRIBUTE) == null;
            if (expect100)
                conversation.setAttribute(EXPECT_100_ATTRIBUTE, Boolean.TRUE);

            ContentInfo contentInfo = this.expectedContent;
            this.expectedContent = null;
            if (contentInfo == null)
                contentInfo = new ContentInfo(contentIterator);
            else
                expect100 = false;

            while (true)
            {
                HttpGenerator.Result result = generator.generateRequest(requestInfo, header, chunk, contentInfo.content, contentInfo.lastContent);
                switch (result)
                {
                    case NEED_INFO:
                    {
                        ContentProvider content = request.getContent();
                        long contentLength = content == null ? -1 : content.getLength();
                        requestInfo = new HttpGenerator.RequestInfo(request.getVersion(), request.getHeaders(), contentLength, request.getMethod().asString(), request.getPath());
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
                        switch (state.get())
                        {
                            case SEND:
                            case COMMIT:
                                break;
                            default:
                                return;
                        }

                        StatefulExecutorCallback callback = new StatefulExecutorCallback(client.getExecutor())
                        {
                            @Override
                            protected void pendingCompleted()
                            {
                                LOG.debug("Write completed for {}", request);

                                if (!commit(request))
                                    return;

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
                            if (!commit(request))
                                return;

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
                        break;
                    }
                    case SHUTDOWN_OUT:
                    {
                        EndPoint endPoint = connection.getEndPoint();
                        endPoint.shutdownOutput();
                        break;
                    }
                    case CONTINUE:
                    {
                        break;
                    }
                    case DONE:
                    {
                        if (generator.isEnd())
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

    protected boolean commit(Request request)
    {
        while (true)
        {
            State current = state.get();
            switch (current)
            {
                case SEND:
                    if (!updateState(current, State.COMMIT))
                        continue;
                    LOG.debug("Committed {}", request);
                    requestNotifier.notifyHeaders(request);
                    return true;
                case COMMIT:
                    return updateState(current, State.COMMIT);
                default:
                    return false;
            }
        }
    }

    protected boolean success()
    {
        HttpExchange exchange = connection.getExchange();
        if (exchange == null)
            return false;

        AtomicMarkableReference<Result> completion = exchange.requestComplete(null);
        if (!completion.isMarked())
            return false;

        generator.reset();

        if (!updateState(State.COMMIT, State.IDLE))
            throw new IllegalStateException();

        exchange.terminateRequest();

        // It is important to notify completion *after* we reset because
        // the notification may trigger another request/response

        Request request = exchange.getRequest();
        requestNotifier.notifySuccess(request);
        LOG.debug("Sent {}", request);

        Result result = completion.getReference();
        if (result != null)
        {
            connection.complete(exchange, !result.isFailed());

            HttpConversation conversation = exchange.getConversation();
            responseNotifier.notifyComplete(conversation.getResponseListener(), result);
        }

        return true;
    }

    protected boolean fail(Throwable failure)
    {
        HttpExchange exchange = connection.getExchange();
        if (exchange == null)
            return false;

        AtomicMarkableReference<Result> completion = exchange.requestComplete(failure);
        if (!completion.isMarked())
            return false;

        generator.abort();

        State current;
        while (true)
        {
            current = state.get();
            if (updateState(current, State.FAILURE))
                break;
        }

        exchange.terminateRequest();

        Request request = exchange.getRequest();
        requestNotifier.notifyFailure(request, failure);
        LOG.debug("Failed {} {}", request, failure);

        Result result = completion.getReference();
        boolean notCommitted = current == State.IDLE || current == State.SEND;
        if (result == null && notCommitted && !request.aborted())
        {
            result = exchange.responseComplete(failure).getReference();
            exchange.terminateResponse();
            LOG.debug("Failed on behalf {}", exchange);
        }

        if (result != null)
        {
            connection.complete(exchange, false);

            HttpConversation conversation = exchange.getConversation();
            responseNotifier.notifyComplete(conversation.getResponseListener(), result);
        }

        return true;
    }

    public boolean abort(HttpExchange exchange, String reason)
    {
        State current = state.get();
        boolean abortable = current == State.IDLE || current == State.SEND ||
                current == State.COMMIT && contentIterator.hasNext();
        return abortable && fail(new HttpRequestException(reason == null ? "Request aborted" : reason, exchange.getRequest()));
    }

    private void releaseBuffers(ByteBufferPool bufferPool, ByteBuffer header, ByteBuffer chunk)
    {
        if (!BufferUtil.hasContent(header))
            bufferPool.release(header);
        if (!BufferUtil.hasContent(chunk))
            bufferPool.release(chunk);
    }

    private boolean updateState(State from, State to)
    {
        boolean updated = state.compareAndSet(from, to);
        if (!updated)
            LOG.debug("State update failed: {} -> {}: {}", from, to, state.get());
        return updated;
    }

    private enum State
    {
        IDLE, SEND, COMMIT, FAILURE
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
                LOG.ignore(x);
            }
        }
    }
}
