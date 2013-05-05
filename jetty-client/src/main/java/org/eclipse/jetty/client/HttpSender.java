//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

public class HttpSender implements AsyncContentProvider.Listener
{
    private static final Logger LOG = Log.getLogger(HttpSender.class);
    private static final String EXPECT_100_ATTRIBUTE = HttpSender.class.getName() + ".expect100";

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
    private final AtomicReference<SendState> sendState = new AtomicReference<>(SendState.IDLE);
    private final HttpGenerator generator = new HttpGenerator();
    private final HttpConnection connection;
    private Iterator<ByteBuffer> contentIterator;
    private ContinueContentChunk continueContentChunk;

    public HttpSender(HttpConnection connection)
    {
        this.connection = connection;
    }

    @Override
    public void onContent()
    {
        while (true)
        {
            SendState current = sendState.get();
            switch (current)
            {
                case IDLE:
                {
                    if (updateSendState(current, SendState.EXECUTE))
                    {
                        LOG.debug("Deferred content available, sending");
                        send();
                        return;
                    }
                    break;
                }
                case EXECUTE:
                {
                    if (updateSendState(current, SendState.SCHEDULE))
                    {
                        LOG.debug("Deferred content available, scheduling");
                        return;
                    }
                    break;
                }
                case SCHEDULE:
                {
                    LOG.debug("Deferred content available, queueing");
                    return;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
    }

    public void send(HttpExchange exchange)
    {
        if (!updateState(State.IDLE, State.BEGIN))
            throw new IllegalStateException();

        Request request = exchange.getRequest();
        Throwable cause = request.getAbortCause();
        if (cause != null)
        {
            exchange.abort(cause);
        }
        else
        {
            LOG.debug("Sending {}", request);
            RequestNotifier notifier = connection.getDestination().getRequestNotifier();
            notifier.notifyBegin(request);

            ContentProvider content = request.getContent();
            this.contentIterator = content == null ? Collections.<ByteBuffer>emptyIterator() : content.iterator();

            boolean updated = updateSendState(SendState.IDLE, SendState.EXECUTE);
            assert updated;

            // Setting the listener may trigger calls to onContent() by other
            // threads so we must set it only after the state has been updated
            if (content instanceof AsyncContentProvider)
                ((AsyncContentProvider)content).setListener(this);

            send();
        }
    }

    private void send()
    {
        SendState currentSendState = sendState.get();
        assert currentSendState != SendState.IDLE : currentSendState;

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

            // Determine whether we have already received the 100 Continue response or not
            // If it was not received yet, we need to save the content and wait for it
            boolean expect100HeaderPresent = request.getHeaders().contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());
            final boolean expecting100ContinueResponse = expect100HeaderPresent && conversation.getAttribute(EXPECT_100_ATTRIBUTE) == null;
            if (expecting100ContinueResponse)
                conversation.setAttribute(EXPECT_100_ATTRIBUTE, Boolean.TRUE);

            ContentChunk contentChunk = continueContentChunk;
            continueContentChunk = null;
            if (contentChunk == null)
                contentChunk = new ContentChunk(contentIterator);

            while (true)
            {
                ByteBuffer content = contentChunk.content;
                final ByteBuffer contentBuffer = content == null ? null : content.slice();

                HttpGenerator.Result result = generator.generateRequest(requestInfo, header, chunk, content, contentChunk.lastContent);
                switch (result)
                {
                    case NEED_INFO:
                    {
                        ContentProvider requestContent = request.getContent();
                        long contentLength = requestContent == null ? -1 : requestContent.getLength();
                        String path = request.getPath();
                        String query = request.getQuery();
                        if (query != null)
                            path += "?" + query;
                        requestInfo = new HttpGenerator.RequestInfo(request.getVersion(), request.getHeaders(), contentLength, request.getMethod().asString(), path);
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
                        out:
                        while (true)
                        {
                            State currentState = state.get();
                            switch (currentState)
                            {
                                case BEGIN:
                                {
                                    if (!updateState(currentState, State.HEADERS))
                                        continue;
                                    RequestNotifier notifier = connection.getDestination().getRequestNotifier();
                                    notifier.notifyHeaders(request);
                                    break out;
                                }
                                case HEADERS:
                                case COMMIT:
                                {
                                    // State update is performed after the write in commit()
                                    break out;
                                }
                                case FAILURE:
                                {
                                    // Failed concurrently, avoid the write since
                                    // the connection is probably already closed
                                    return;
                                }
                                default:
                                {
                                    throw new IllegalStateException();
                                }
                            }
                        }

                        StatefulExecutorCallback callback = new StatefulExecutorCallback(client.getExecutor())
                        {
                            @Override
                            protected void onSucceeded()
                            {
                                LOG.debug("Write succeeded for {}", request);

                                if (!processWrite(request, contentBuffer, expecting100ContinueResponse))
                                    return;

                                send();
                            }

                            @Override
                            protected void onFailed(Throwable x)
                            {
                                fail(x);
                            }
                        };

                        if (expecting100ContinueResponse)
                        {
                            // Save the content waiting for the 100 Continue response
                            continueContentChunk = new ContinueContentChunk(contentChunk);
                        }

                        write(callback, header, chunk, expecting100ContinueResponse ? null : content);

                        if (callback.process())
                        {
                            LOG.debug("Write pending for {}", request);
                            return;
                        }

                        if (callback.isSucceeded())
                        {
                            if (!processWrite(request, contentBuffer, expecting100ContinueResponse))
                                return;

                            // Send further content
                            contentChunk = new ContentChunk(contentIterator);

                            if (contentChunk.isDeferred())
                            {
                                out:
                                while (true)
                                {
                                    currentSendState = sendState.get();
                                    switch (currentSendState)
                                    {
                                        case EXECUTE:
                                        {
                                            if (updateSendState(currentSendState, SendState.IDLE))
                                            {
                                                LOG.debug("Waiting for deferred content for {}", request);
                                                return;
                                            }
                                            break;
                                        }
                                        case SCHEDULE:
                                        {
                                            if (updateSendState(currentSendState, SendState.EXECUTE))
                                            {
                                                LOG.debug("Deferred content available for {}", request);
                                                break out;
                                            }
                                            break;
                                        }
                                        default:
                                        {
                                            throw new IllegalStateException();
                                        }
                                    }
                                }
                            }
                        }
                        break;
                    }
                    case SHUTDOWN_OUT:
                    {
                        shutdownOutput();
                        break;
                    }
                    case CONTINUE:
                    {
                        break;
                    }
                    case DONE:
                    {
                        if (generator.isEnd())
                        {
                            out: while (true)
                            {
                                currentSendState = sendState.get();
                                switch (currentSendState)
                                {
                                    case EXECUTE:
                                    case SCHEDULE:
                                    {
                                        if (!updateSendState(currentSendState, SendState.IDLE))
                                            throw new IllegalStateException();
                                        break out;
                                    }
                                    default:
                                    {
                                        throw new IllegalStateException();
                                    }
                                }
                            }
                            success();
                        }
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

    private boolean processWrite(Request request, ByteBuffer content, boolean expecting100ContinueResponse)
    {
        if (!commit(request))
            return false;

        if (content != null)
        {
            RequestNotifier notifier = connection.getDestination().getRequestNotifier();
            notifier.notifyContent(request, content);
        }

        if (expecting100ContinueResponse)
        {
            LOG.debug("Expecting 100 Continue for {}", request);
            continueContentChunk.signal();
            return false;
        }

        return true;
    }

    public void proceed(boolean proceed)
    {
        ContinueContentChunk contentChunk = continueContentChunk;
        if (contentChunk != null)
        {
            if (proceed)
            {
                // Method send() must not be executed concurrently.
                // The write in send() may arrive to the server and the server reply with 100 Continue
                // before send() exits; the processing of the 100 Continue will invoke this method
                // which in turn invokes send(), with the risk of a concurrent invocation of send().
                // Therefore we wait here on the ContinueContentChunk to send, and send() will signal
                // when it is ok to proceed.
                LOG.debug("Proceeding {}", connection.getExchange());
                contentChunk.await();
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

    private void write(Callback callback, ByteBuffer header, ByteBuffer chunk, ByteBuffer content)
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
                endPoint.write(callback, BufferUtil.EMPTY_BUFFER);
                break;
            case 1:
                endPoint.write(callback, header);
                break;
            case 2:
                endPoint.write(callback, chunk);
                break;
            case 3:
                endPoint.write(callback, header, chunk);
                break;
            case 4:
                endPoint.write(callback, content);
                break;
            case 5:
                endPoint.write(callback, header, content);
                break;
            case 6:
                endPoint.write(callback, chunk, content);
                break;
            case 7:
                endPoint.write(callback, header, chunk, content);
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
                case HEADERS:
                    if (!updateState(current, State.COMMIT))
                        continue;
                    LOG.debug("Committed {}", request);
                    RequestNotifier notifier = connection.getDestination().getRequestNotifier();
                    notifier.notifyCommit(request);
                    return true;
                case COMMIT:
                    if (!updateState(current, State.COMMIT))
                        continue;
                    return true;
                case FAILURE:
                    return false;
                default:
                    throw new IllegalStateException();
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

        HttpDestination destination = connection.getDestination();
        Request request = exchange.getRequest();
        destination.getRequestNotifier().notifySuccess(request);
        LOG.debug("Sent {}", request);

        Result result = completion.getReference();
        if (result != null)
        {
            connection.complete(exchange, !result.isFailed());

            HttpConversation conversation = exchange.getConversation();
            destination.getResponseNotifier().notifyComplete(conversation.getResponseListeners(), result);
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

        shutdownOutput();

        exchange.terminateRequest();

        HttpDestination destination = connection.getDestination();
        Request request = exchange.getRequest();
        destination.getRequestNotifier().notifyFailure(request, failure);
        LOG.debug("Failed {} {}", request, failure);

        Result result = completion.getReference();
        boolean notCommitted = isBeforeCommit(current);
        if (result == null && notCommitted && request.getAbortCause() == null)
        {
            result = exchange.responseComplete(failure).getReference();
            exchange.terminateResponse();
            LOG.debug("Failed on behalf {}", exchange);
        }

        if (result != null)
        {
            connection.complete(exchange, false);

            HttpConversation conversation = exchange.getConversation();
            destination.getResponseNotifier().notifyComplete(conversation.getResponseListeners(), result);
        }

        return true;
    }

    private void shutdownOutput()
    {
        connection.getEndPoint().shutdownOutput();
    }

    public boolean abort(Throwable cause)
    {
        State current = state.get();
        boolean abortable = isBeforeCommit(current) ||
                current == State.COMMIT && contentIterator.hasNext();
        return abortable && fail(cause);
    }

    private boolean isBeforeCommit(State state)
    {
        return state == State.IDLE || state == State.BEGIN || state == State.HEADERS;
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

    private boolean updateSendState(SendState from, SendState to)
    {
        boolean updated = sendState.compareAndSet(from, to);
        if (!updated)
            LOG.debug("Send state update failed: {} -> {}: {}", from, to, sendState.get());
        return updated;
    }

    private enum State
    {
        IDLE, BEGIN, HEADERS, COMMIT, FAILURE
    }

    private enum SendState
    {
        IDLE, EXECUTE, SCHEDULE
    }

    private static abstract class StatefulExecutorCallback implements Callback, Runnable
    {
        private final AtomicReference<State> state = new AtomicReference<>(State.INCOMPLETE);
        private final Executor executor;

        private StatefulExecutorCallback(Executor executor)
        {
            this.executor = executor;
        }

        @Override
        public final void succeeded()
        {
            State previous = state.get();
            while (true)
            {
                if (state.compareAndSet(previous, State.SUCCEEDED))
                    break;
                previous = state.get();
            }
            if (previous == State.PENDING)
                executor.execute(this);
        }

        @Override
        public final void run()
        {
            onSucceeded();
        }

        protected abstract void onSucceeded();

        @Override
        public final void failed(final Throwable x)
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
                        onFailed(x);
                    }
                });
            }
            else
            {
                onFailed(x);
            }
        }

        protected abstract void onFailed(Throwable x);

        public boolean process()
        {
            return state.compareAndSet(State.INCOMPLETE, State.PENDING);
        }

        public boolean isSucceeded()
        {
            return state.get() == State.SUCCEEDED;
        }

        public boolean isFailed()
        {
            return state.get() == State.FAILED;
        }

        private enum State
        {
            INCOMPLETE, PENDING, SUCCEEDED, FAILED
        }
    }

    private class ContentChunk
    {
        private final boolean lastContent;
        private final ByteBuffer content;

        private ContentChunk(ContentChunk chunk)
        {
            lastContent = chunk.lastContent;
            content = chunk.content;
        }

        private ContentChunk(Iterator<ByteBuffer> contentIterator)
        {
            lastContent = !contentIterator.hasNext();
            content = lastContent ? BufferUtil.EMPTY_BUFFER : contentIterator.next();
        }

        private boolean isDeferred()
        {
            return content == null && !lastContent;
        }
    }

    private class ContinueContentChunk extends ContentChunk
    {
        private final CountDownLatch latch = new CountDownLatch(1);

        private ContinueContentChunk(ContentChunk chunk)
        {
            super(chunk);
        }

        private void signal()
        {
            latch.countDown();
        }

        private void await()
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
