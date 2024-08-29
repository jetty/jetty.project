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

package org.eclipse.jetty.client.transport;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>HttpSender abstracts the algorithm to send HTTP requests, so that subclasses only
 * implement the transport-specific code to send requests over the wire, implementing
 * {@link #sendHeaders(HttpExchange, ByteBuffer, boolean, Callback)} and
 * {@link #sendContent(HttpExchange, ByteBuffer, boolean, Callback)}.</p>
 * <p>HttpSender governs the request state machines, which is updated as the various
 * steps of sending a request are executed, see {@code RequestState}.
 * At any point in time, a user thread may abort the request, which may (if the request
 * has not been completely sent yet) move the request state machine to {@code RequestState#FAILURE}.
 * The request state machine guarantees that the request steps are executed (by I/O threads)
 * only if the request has not been failed already.</p>
 *
 * @see HttpReceiver
 */
public abstract class HttpSender
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpSender.class);

    private final ContentSender contentSender = new ContentSender();
    private final AtomicReference<RequestState> requestState = new AtomicReference<>(RequestState.QUEUED);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final HttpChannel channel;

    protected HttpSender(HttpChannel channel)
    {
        this.channel = channel;
    }

    protected HttpChannel getHttpChannel()
    {
        return channel;
    }

    protected HttpExchange getHttpExchange()
    {
        return channel.getHttpExchange();
    }

    public boolean isFailed()
    {
        return requestState.get() == RequestState.FAILURE;
    }

    public void send(HttpExchange exchange)
    {
        if (!queuedToBegin(exchange))
            return;

        if (!beginToHeaders(exchange))
            return;

        contentSender.iterate();
    }

    protected boolean expects100Continue(Request request)
    {
        return request.getHeaders().contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());
    }

    protected boolean queuedToBegin(HttpExchange exchange)
    {
        if (!updateRequestState(RequestState.QUEUED, RequestState.TRANSIENT))
            return false;

        HttpRequest request = exchange.getRequest();
        if (LOG.isDebugEnabled())
            LOG.debug("Request begin {}", request);
        request.notifyBegin();

        contentSender.expect100 = expects100Continue(request);

        if (updateRequestState(RequestState.TRANSIENT, RequestState.BEGIN))
            return true;

        abortRequest(exchange);
        return false;
    }

    protected boolean beginToHeaders(HttpExchange exchange)
    {
        if (!updateRequestState(RequestState.BEGIN, RequestState.TRANSIENT))
            return false;

        HttpRequest request = exchange.getRequest();
        if (LOG.isDebugEnabled())
            LOG.debug("Request headers {}{}{}", request, System.lineSeparator(), request.getHeaders().toString().trim());
        request.notifyHeaders();

        if (updateRequestState(RequestState.TRANSIENT, RequestState.HEADERS))
            return true;

        abortRequest(exchange);
        return false;
    }

    protected boolean headersToCommit(HttpExchange exchange)
    {
        if (!updateRequestState(RequestState.HEADERS, RequestState.TRANSIENT))
            return false;

        HttpRequest request = exchange.getRequest();
        if (LOG.isDebugEnabled())
            LOG.debug("Request committed {}", request);
        request.notifyCommit();

        if (updateRequestState(RequestState.TRANSIENT, RequestState.COMMIT))
            return true;

        abortRequest(exchange);
        return false;
    }

    protected boolean someToContent(HttpExchange exchange, ByteBuffer content)
    {
        RequestState current = requestState.get();
        return switch (current)
        {
            case COMMIT, CONTENT ->
            {
                if (!updateRequestState(current, RequestState.TRANSIENT))
                    yield false;

                HttpRequest request = exchange.getRequest();
                if (LOG.isDebugEnabled())
                    LOG.debug("Request content {}{}{}", request, System.lineSeparator(), BufferUtil.toDetailString(content));
                request.notifyContent(content);

                if (updateRequestState(RequestState.TRANSIENT, RequestState.CONTENT))
                    yield true;

                abortRequest(exchange);
                yield false;
            }
            default -> false;
        };
    }

    protected boolean someToSuccess(HttpExchange exchange)
    {
        RequestState current = requestState.get();
        return switch (current)
        {
            case COMMIT, CONTENT ->
            {
                // Mark atomically the request as completed, with respect
                // to concurrency between request success and request failure.
                if (!exchange.requestComplete(null))
                    yield false;

                requestState.set(RequestState.QUEUED);

                // Reset to be ready for another request.
                reset();

                HttpRequest request = exchange.getRequest();
                if (LOG.isDebugEnabled())
                    LOG.debug("Request success {}", request);
                request.notifySuccess();

                // Mark atomically the request as terminated, with
                // respect to concurrency between request and response.
                Result result = exchange.terminateRequest();
                terminateRequest(exchange, null, result);
                yield true;
            }
            default -> false;
        };
    }

    private boolean failRequest(Throwable failure)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return false;

        if (LOG.isDebugEnabled())
            LOG.debug("Request failure {}, response {}", exchange.getRequest(), exchange.getResponse(), failure);

        // Mark atomically the request as completed, with respect
        // to concurrency between request success and request failure.
        return exchange.requestComplete(failure);
    }

    private void executeAbort(HttpExchange exchange, Throwable failure)
    {
        try
        {
            Executor executor = getHttpChannel().getHttpDestination().getHttpClient().getExecutor();
            executor.execute(() -> abort(exchange, failure, Promise.noop()));
        }
        catch (RejectedExecutionException x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Exchange aborted {}", exchange, x);
            abort(exchange, failure, Promise.noop());
        }
    }

    private void abortRequest(HttpExchange exchange)
    {
        Throwable failure = this.failure.get();

        HttpRequest request = exchange.getRequest();
        Content.Source content = request.getBody();
        if (content != null)
            content.fail(failure);

        dispose();

        if (LOG.isDebugEnabled())
            LOG.debug("Request abort {} {} on {}", request, exchange, getHttpChannel(), failure);
        request.notifyFailure(failure);

        // Mark atomically the request as terminated, with
        // respect to concurrency between request and response.
        Result result = exchange.terminateRequest();
        terminateRequest(exchange, failure, result);
    }

    private void terminateRequest(HttpExchange exchange, Throwable failure, Result result)
    {
        HttpRequest request = exchange.getRequest();

        if (LOG.isDebugEnabled())
            LOG.debug("Terminating request {}", request);

        if (result == null)
        {
            if (failure != null)
            {
                if (exchange.responseComplete(failure))
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Response failure from request {} {}", request, exchange);
                    getHttpChannel().abortResponse(exchange, failure, Promise.noop());
                }
            }
        }
        else
        {
            result = channel.exchangeTerminating(exchange, result);
            HttpDestination destination = getHttpChannel().getHttpDestination();
            boolean ordered = destination.getHttpClient().isStrictEventOrdering();
            if (!ordered)
                channel.exchangeTerminated(exchange, result);
            if (LOG.isDebugEnabled())
                LOG.debug("Request/Response {}: {}", failure == null ? "succeeded" : "failed", result);
            exchange.getConversation().getResponseListeners().notifyComplete(result);
            if (ordered)
                channel.exchangeTerminated(exchange, result);
        }
    }

    /**
     * <p>Implementations should send the HTTP headers over the wire, possibly with some content,
     * in a single write, and notify the given {@code callback} of the result of this operation.</p>
     * <p>If there is more content to send, then {@link #sendContent(HttpExchange, ByteBuffer, boolean, Callback)}
     * will be invoked.</p>
     *
     * @param exchange the exchange
     * @param contentBuffer the content to send
     * @param lastContent whether the content is the last content to send
     * @param callback the callback to notify
     */
    protected abstract void sendHeaders(HttpExchange exchange, ByteBuffer contentBuffer, boolean lastContent, Callback callback);

    /**
     * <p>Implementations should send the given HTTP content over the wire.</p>
     *
     * @param exchange the exchange
     * @param contentBuffer the content to send
     * @param lastContent whether the content is the last content to send
     * @param callback the callback to notify
     */
    protected abstract void sendContent(HttpExchange exchange, ByteBuffer contentBuffer, boolean lastContent, Callback callback);

    protected void reset()
    {
        contentSender.reset();
    }

    protected void dispose()
    {
    }

    public void proceed(HttpExchange exchange, Runnable proceedAction, Throwable failure)
    {
        // Received a 100 Continue, although the Expect header was not sent.
        if (!contentSender.expect100)
            return;

        // Write the fields in this order, since the reader of
        // these fields will read them in the opposite order.
        contentSender.proceedAction = proceedAction;
        contentSender.expect100 = false;
        if (failure == null)
        {
            contentSender.iterate();
        }
        else
        {
            if (failRequest(failure))
                executeAbort(exchange, failure);
        }
    }

    public void abort(HttpExchange exchange, Throwable failure, Promise<Boolean> promise)
    {
        externalAbort(failure, promise);
    }

    private boolean anyToFailure(Throwable failure)
    {
        // Store only the first failure.
        this.failure.compareAndSet(null, failure);

        // Update the state to avoid more request processing.
        boolean abort;
        while (true)
        {
            RequestState current = requestState.get();
            if (current == RequestState.FAILURE)
            {
                abort = false;
                break;
            }
            else
            {
                if (updateRequestState(current, RequestState.FAILURE))
                {
                    abort = current != RequestState.TRANSIENT;
                    break;
                }
            }
        }
        return abort;
    }

    private void externalAbort(Throwable failure, Promise<Boolean> promise)
    {
        boolean abort = anyToFailure(failure);
        if (abort)
        {
            contentSender.abort = promise;
            contentSender.abort(this.failure.get());
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Concurrent failure: request termination skipped, performed by helpers");
            promise.succeeded(false);
        }
    }

    private void internalAbort(Throwable failure)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
        {
            LOG.info("ISSUE-11841 no exchange in internalAbort - {}", this);
            return;
        }
        anyToFailure(failure);
        abortRequest(exchange);
    }

    private boolean updateRequestState(RequestState from, RequestState to)
    {
        boolean updated = requestState.compareAndSet(from, to);
        if (!updated && LOG.isDebugEnabled())
            LOG.debug("RequestState update failed: {} -> {}: {}", from, to, requestState.get());
        return updated;
    }

    protected String relativize(String path)
    {
        try
        {
            String result = path;
            URI uri = URI.create(result);
            if (uri.isAbsolute())
                result = uri.getPath();
            return result.isEmpty() ? "/" : result;
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Could not relativize {}", path);
            return path;
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x(req=%s,channel=%s,cs=%s,failure=%s)",
            getClass().getSimpleName(),
            hashCode(),
            requestState,
            channel,
            contentSender,
            failure);
    }

    /**
     * The request states {@link HttpSender} goes through when sending a request.
     */
    private enum RequestState
    {
        /**
         * One of the state transition methods is being executed.
         */
        TRANSIENT,
        /**
         * The request is queued, the initial state
         */
        QUEUED,
        /**
         * The request has been dequeued
         */
        BEGIN,
        /**
         * The request headers (and possibly some content) is about to be sent
         */
        HEADERS,
        /**
         * The request headers (and possibly some content) have been sent
         */
        COMMIT,
        /**
         * The request content is being sent
         */
        CONTENT,
        /**
         * The request is failed
         */
        FAILURE
    }

    private class ContentSender extends IteratingCallback
    {
        // Fields that are set externally.
        private volatile Runnable proceedAction;
        private volatile boolean expect100;
        // Fields only used internally.
        private Content.Chunk chunk;
        private ByteBuffer contentBuffer;
        private boolean committed;
        private boolean success;
        private boolean complete;
        private Promise<Boolean> abort;
        private boolean demanded;

        @Override
        public boolean reset()
        {
            proceedAction = null;
            expect100 = false;
            chunk = null;
            contentBuffer = null;
            committed = false;
            success = false;
            complete = false;
            abort = null;
            demanded = false;
            return super.reset();
        }

        @Override
        protected Action process() throws Throwable
        {
            HttpExchange exchange = getHttpExchange();
            if (exchange == null)
            {
                LOG.info("ISSUE-11841 no exchange in process - {}", HttpSender.this);
                return Action.IDLE;
            }
            if (complete)
            {
                if (success)
                    someToSuccess(exchange);
                return Action.IDLE;
            }

            HttpRequest request = exchange.getRequest();
            Content.Source content = request.getBody();

            boolean expect100 = this.expect100;
            if (expect100)
            {
                // If the request was sent already, wait for
                // the 100 response before sending the content.
                if (committed)
                    return Action.IDLE;
                // Do not send any content yet.
                chunk = null;
            }
            else
            {
                // Run the proceed action first, which likely will provide
                // content after having received the 100 Continue response.
                Runnable action = proceedAction;
                proceedAction = null;
                if (action != null)
                    action.run();

                // Read the request content.
                chunk = content != null ? content.read() : Content.Chunk.EOF;
            }
            if (LOG.isDebugEnabled())
                LOG.debug("Content {} for {}", chunk, request);

            if (chunk == null)
            {
                if (committed)
                {
                    // No content after the headers, demand.
                    demanded = true;
                    assert content != null;
                    content.demand(this::succeeded);
                    return Action.SCHEDULED;
                }
                else
                {
                    // Normalize to avoid null checks.
                    chunk = Content.Chunk.EMPTY;
                }
            }

            if (Content.Chunk.isFailure(chunk))
            {
                Content.Chunk failure = chunk;
                chunk = Content.Chunk.next(failure);
                throw failure.getFailure();
            }

            ByteBuffer buffer = chunk.getByteBuffer();
            contentBuffer = buffer.asReadOnlyBuffer();
            boolean last = chunk.isLast();
            if (committed)
                sendContent(exchange, buffer, last, this);
            else
                sendHeaders(exchange, buffer, last, this);
            return Action.SCHEDULED;
        }

        @Override
        protected void onSuccess()
        {
            if (demanded)
            {
                // Content is now available, reset
                // the demand and iterate again.
                demanded = false;
            }
            else
            {
                HttpExchange exchange = getHttpExchange();
                if (exchange == null)
                {
                    LOG.info("ISSUE-11841 no exchange in onSuccess - {}", HttpSender.this);
                    if (chunk != null)
                    {
                        LOG.info("ISSUE-11841 releasing chunk - {}", HttpSender.this);
                        chunk.release();
                        chunk = null;
                    }
                    return;
                }

                boolean proceed = true;
                if (committed)
                {
                    if (contentBuffer.hasRemaining())
                        proceed = someToContent(exchange, contentBuffer);
                }
                else
                {
                    committed = true;
                    proceed = headersToCommit(exchange);
                    if (proceed)
                    {
                        // Was any content sent while committing?
                        if (contentBuffer.hasRemaining())
                            proceed = someToContent(exchange, contentBuffer);
                    }
                }

                boolean last = chunk.isLast();
                chunk.release();
                chunk = null;

                if (proceed)
                {
                    if (last)
                    {
                        success = true;
                        complete = true;
                    }
                }
                else
                {
                    // There was some concurrent error, terminate.
                    complete = true;
                }
            }
        }

        @Override
        protected void onCompleteFailure(Throwable x)
        {
            if (chunk != null)
            {
                chunk.release();
                chunk = Content.Chunk.next(chunk);
            }

            failRequest(x);
            internalAbort(x);

            Promise<Boolean> promise = abort;
            if (promise != null)
                promise.succeeded(true);
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        @Override
        public String toString()
        {
            return super.toString() +
                " proceedAction=" + proceedAction +
                " expect100=" + expect100 +
                " chunk=" + chunk +
                " contentBuffer=" + BufferUtil.toDetailString(contentBuffer) +
                " committed=" + committed +
                " success=" + success +
                " complete=" + complete +
                " abort=" + abort +
                " demanded=" + demanded;
        }
    }
}
