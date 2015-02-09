//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * {@link HttpSender} abstracts the algorithm to send HTTP requests, so that subclasses only implement
 * the transport-specific code to send requests over the wire, implementing
 * {@link #sendHeaders(HttpExchange, HttpContent, Callback)} and
 * {@link #sendContent(HttpExchange, HttpContent, Callback)}.
 * <p />
 * {@link HttpSender} governs two state machines.
 * <p />
 * The request state machine is updated by {@link HttpSender} as the various steps of sending a request
 * are executed, see {@link RequestState}.
 * At any point in time, a user thread may abort the request, which may (if the request has not been
 * completely sent yet) move the request state machine to {@link RequestState#FAILURE}.
 * The request state machine guarantees that the request steps are executed (by I/O threads) only if
 * the request has not been failed already.
 * <p />
 * The sender state machine is updated by {@link HttpSender} from three sources: deferred content notifications
 * (via {@link #onContent()}), 100-continue notifications (via {@link #proceed(HttpExchange, Throwable)})
 * and normal request send (via {@link #sendContent(HttpExchange, HttpContent, Callback)}).
 * This state machine must guarantee that the request sending is never executed concurrently: only one of
 * those sources may trigger the call to {@link #sendContent(HttpExchange, HttpContent, Callback)}.
 *
 * @see HttpReceiver
 */
public abstract class HttpSender implements AsyncContentProvider.Listener
{
    protected static final Logger LOG = Log.getLogger(HttpSender.class);

    private final AtomicReference<RequestState> requestState = new AtomicReference<>(RequestState.QUEUED);
    private final AtomicReference<SenderState> senderState = new AtomicReference<>(SenderState.IDLE);
    private final Callback commitCallback = new CommitCallback();
    private final IteratingCallback contentCallback = new ContentCallback();
    private final Callback lastCallback = new LastContentCallback();
    private final HttpChannel channel;
    private HttpContent content;
    private Throwable failure;

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

    @Override
    public void onContent()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return;

        while (true)
        {
            SenderState current = senderState.get();
            switch (current)
            {
                case IDLE:
                {
                    SenderState newSenderState = SenderState.SENDING;
                    if (updateSenderState(current, newSenderState))
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Deferred content available, {} -> {}", current, newSenderState);
                        contentCallback.iterate();
                        return;
                    }
                    break;
                }
                case SENDING:
                {
                    SenderState newSenderState = SenderState.SENDING_WITH_CONTENT;
                    if (updateSenderState(current, newSenderState))
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Deferred content available, {} -> {}", current, newSenderState);
                        return;
                    }
                    break;
                }
                case EXPECTING:
                {
                    SenderState newSenderState = SenderState.EXPECTING_WITH_CONTENT;
                    if (updateSenderState(current, newSenderState))
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Deferred content available, {} -> {}", current, newSenderState);
                        return;
                    }
                    break;
                }
                case PROCEEDING:
                {
                    SenderState newSenderState = SenderState.PROCEEDING_WITH_CONTENT;
                    if (updateSenderState(current, newSenderState))
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Deferred content available, {} -> {}", current, newSenderState);
                        return;
                    }
                    break;
                }
                case SENDING_WITH_CONTENT:
                case EXPECTING_WITH_CONTENT:
                case PROCEEDING_WITH_CONTENT:
                case WAITING:
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Deferred content available, {}", current);
                    return;
                }
                default:
                {
                    throw illegalSenderState(current);
                }
            }
        }
    }

    public void send(HttpExchange exchange)
    {
        Request request = exchange.getRequest();
        if (!queuedToBegin(request))
            return;

        ContentProvider contentProvider = request.getContent();
        HttpContent content = this.content = new HttpContent(contentProvider);

        SenderState newSenderState = SenderState.SENDING;
        if (expects100Continue(request))
            newSenderState = content.hasContent() ? SenderState.EXPECTING_WITH_CONTENT : SenderState.EXPECTING;
        if (!updateSenderState(SenderState.IDLE, newSenderState))
            throw illegalSenderState(SenderState.IDLE);

        // Setting the listener may trigger calls to onContent() by other
        // threads so we must set it only after the sender state has been updated
        if (contentProvider instanceof AsyncContentProvider)
            ((AsyncContentProvider)contentProvider).setListener(this);

        if (!beginToHeaders(request))
            return;

        sendHeaders(exchange, content, commitCallback);
    }

    protected boolean expects100Continue(Request request)
    {
        return request.getHeaders().contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());
    }

    protected boolean queuedToBegin(Request request)
    {
        if (!updateRequestState(RequestState.QUEUED, RequestState.TRANSIENT))
            return false;
        if (LOG.isDebugEnabled())
            LOG.debug("Request begin {}", request);
        RequestNotifier notifier = getHttpChannel().getHttpDestination().getRequestNotifier();
        notifier.notifyBegin(request);
        if (!updateRequestState(RequestState.TRANSIENT, RequestState.BEGIN))
            terminateRequest(getHttpExchange(), failure, false);
        return true;
    }

    protected boolean beginToHeaders(Request request)
    {
        if (!updateRequestState(RequestState.BEGIN, RequestState.TRANSIENT))
            return false;
        if (LOG.isDebugEnabled())
            LOG.debug("Request headers {}{}{}", request, System.getProperty("line.separator"), request.getHeaders().toString().trim());
        RequestNotifier notifier = getHttpChannel().getHttpDestination().getRequestNotifier();
        notifier.notifyHeaders(request);
        if (!updateRequestState(RequestState.TRANSIENT, RequestState.HEADERS))
            terminateRequest(getHttpExchange(), failure, false);
        return true;
    }

    protected boolean headersToCommit(Request request)
    {
        if (!updateRequestState(RequestState.HEADERS, RequestState.TRANSIENT))
            return false;
        if (LOG.isDebugEnabled())
            LOG.debug("Request committed {}", request);
        RequestNotifier notifier = getHttpChannel().getHttpDestination().getRequestNotifier();
        notifier.notifyCommit(request);
        if (!updateRequestState(RequestState.TRANSIENT, RequestState.COMMIT))
            terminateRequest(getHttpExchange(), failure, true);
        return true;
    }

    protected boolean someToContent(Request request, ByteBuffer content)
    {
        RequestState current = requestState.get();
        switch (current)
        {
            case COMMIT:
            case CONTENT:
            {
                if (!updateRequestState(current, RequestState.TRANSIENT_CONTENT))
                    return false;
                if (LOG.isDebugEnabled())
                    LOG.debug("Request content {}{}{}", request, System.getProperty("line.separator"), BufferUtil.toDetailString(content));
                RequestNotifier notifier = getHttpChannel().getHttpDestination().getRequestNotifier();
                notifier.notifyContent(request, content);
                if (!updateRequestState(RequestState.TRANSIENT_CONTENT, RequestState.CONTENT))
                    terminateRequest(getHttpExchange(), failure, true);
                return true;
            }
            default:
            {
                return false;
            }
        }
    }

    protected boolean someToSuccess(HttpExchange exchange)
    {
        RequestState current = requestState.get();
        switch (current)
        {
            case COMMIT:
            case CONTENT:
            {
                // Mark atomically the request as completed, with respect
                // to concurrency between request success and request failure.
                boolean completed = exchange.requestComplete();
                if (!completed)
                    return false;

                requestState.set(RequestState.QUEUED);

                // Reset to be ready for another request.
                reset();

                // Mark atomically the request as terminated and succeeded,
                // with respect to concurrency between request and response.
                Result result = exchange.terminateRequest(null);

                Request request = exchange.getRequest();
                if (LOG.isDebugEnabled())
                    LOG.debug("Request success {}", request);
                HttpDestination destination = getHttpChannel().getHttpDestination();
                destination.getRequestNotifier().notifySuccess(exchange.getRequest());

                terminateRequest(exchange, null, true, result);

                return true;
            }
            default:
            {
                return false;
            }
        }
    }

    protected boolean anyToFailure(Throwable failure)
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange == null)
            return false;

        // Mark atomically the request as completed, with respect
        // to concurrency between request success and request failure.
        boolean completed = exchange.requestComplete();
        if (!completed)
            return false;

        this.failure = failure;

        // Update the state to avoid more request processing.
        RequestState current;
        boolean fail;
        while (true)
        {
            current = requestState.get();
            if (updateRequestState(current, RequestState.FAILURE))
            {
                fail = current != RequestState.TRANSIENT && current != RequestState.TRANSIENT_CONTENT;
                break;
            }
        }

        dispose();

        // Mark atomically the request as terminated and failed,
        // with respect to concurrency between request and response.
        Result result = exchange.terminateRequest(failure);

        Request request = exchange.getRequest();
        if (LOG.isDebugEnabled())
            LOG.debug("Request failure {} {}", exchange, failure);
        HttpDestination destination = getHttpChannel().getHttpDestination();
        destination.getRequestNotifier().notifyFailure(request, failure);

        if (fail)
        {
            terminateRequest(exchange, failure, !isBeforeCommit(current), result);
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Concurrent failure: request termination skipped, performed by helpers");
        }

        return true;
    }

    private void terminateRequest(HttpExchange exchange, Throwable failure, boolean committed)
    {
        if (exchange != null)
        {
            Result result = exchange.terminateRequest(failure);
            terminateRequest(exchange, failure, committed, result);
        }
    }

    private void terminateRequest(HttpExchange exchange, Throwable failure, boolean committed, Result result)
    {
        Request request = exchange.getRequest();

        if (LOG.isDebugEnabled())
            LOG.debug("Terminating request {}", request);

        if (failure != null && !committed && result == null && request.getAbortCause() == null)
        {
            // Complete the response from here
            if (exchange.responseComplete())
            {
                result = exchange.terminateResponse(failure);
                if (LOG.isDebugEnabled())
                    LOG.debug("Failed response from request {}", exchange);
            }
        }

        if (result != null)
        {
            HttpDestination destination = getHttpChannel().getHttpDestination();
            boolean ordered = destination.getHttpClient().isStrictEventOrdering();
            if (!ordered)
                channel.exchangeTerminated(result);
            if (LOG.isDebugEnabled())
                LOG.debug("Request/Response {} {}", failure == null ? "succeeded" : "failed", request);
            HttpConversation conversation = exchange.getConversation();
            destination.getResponseNotifier().notifyComplete(conversation.getResponseListeners(), result);
            if (ordered)
                channel.exchangeTerminated(result);
        }
    }

    /**
     * Implementations should send the HTTP headers over the wire, possibly with some content,
     * in a single write, and notify the given {@code callback} of the result of this operation.
     * <p />
     * If there is more content to send, then {@link #sendContent(HttpExchange, HttpContent, Callback)}
     * will be invoked.
     *
     * @param exchange the exchange to send
     * @param content the content to send
     * @param callback the callback to notify
     */
    protected abstract void sendHeaders(HttpExchange exchange, HttpContent content, Callback callback);

    /**
     * Implementations should send the content at the {@link HttpContent} cursor position over the wire.
     * <p />
     * The {@link HttpContent} cursor is advanced by {@link HttpSender} at the right time, and if more
     * content needs to be sent, this method is invoked again; subclasses need only to send the content
     * at the {@link HttpContent} cursor position.
     * <p />
     * This method is invoked one last time when {@link HttpContent#isConsumed()} is true and therefore
     * there is no actual content to send.
     * This is done to allow subclasses to write "terminal" bytes (such as the terminal chunk when the
     * transfer encoding is chunked) if their protocol needs to.
     *
     * @param exchange the exchange to send
     * @param content the content to send
     * @param callback the callback to notify
     */
    protected abstract void sendContent(HttpExchange exchange, HttpContent content, Callback callback);

    protected void reset()
    {
        content.close();
        content = null;
        senderState.set(SenderState.IDLE);
    }

    protected void dispose()
    {
        HttpContent content = this.content;
        if (content != null)
            content.close();
    }

    public void proceed(HttpExchange exchange, Throwable failure)
    {
        if (!expects100Continue(exchange.getRequest()))
            return;

        if (failure != null)
        {
            anyToFailure(failure);
            return;
        }

        while (true)
        {
            SenderState current = senderState.get();
            switch (current)
            {
                case EXPECTING:
                {
                    // We are still sending the headers, but we already got the 100 Continue.
                    if (updateSenderState(current, SenderState.PROCEEDING))
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Proceeding while expecting");
                        return;
                    }
                    break;
                }
                case EXPECTING_WITH_CONTENT:
                {
                    // More deferred content was submitted to onContent(), we already
                    // got the 100 Continue, but we may be still sending the headers
                    // (for example, with SSL we may have sent the encrypted data,
                    // received the 100 Continue but not yet updated the decrypted
                    // WriteFlusher so sending more content now may result in a
                    // WritePendingException).
                    if (updateSenderState(current, SenderState.PROCEEDING_WITH_CONTENT))
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Proceeding while scheduled");
                        return;
                    }
                    break;
                }
                case WAITING:
                {
                    // We received the 100 Continue, now send the content if any.
                    if (!updateSenderState(current, SenderState.SENDING))
                        throw illegalSenderState(current);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Proceeding while waiting");
                    contentCallback.iterate();
                    return;
                }
                default:
                {
                    throw illegalSenderState(current);
                }
            }
        }
    }

    public boolean abort(Throwable failure)
    {
        RequestState current = requestState.get();
        boolean abortable = isBeforeCommit(current) || isSending(current);
        return abortable && anyToFailure(failure);
    }

    private boolean updateRequestState(RequestState from, RequestState to)
    {
        boolean updated = requestState.compareAndSet(from, to);
        if (!updated)
            LOG.debug("RequestState update failed: {} -> {}: {}", from, to, requestState.get());
        return updated;
    }

    private boolean updateSenderState(SenderState from, SenderState to)
    {
        boolean updated = senderState.compareAndSet(from, to);
        if (!updated)
            LOG.debug("SenderState update failed: {} -> {}: {}", from, to, senderState.get());
        return updated;
    }

    private boolean isBeforeCommit(RequestState requestState)
    {
        switch (requestState)
        {
            case TRANSIENT:
            case QUEUED:
            case BEGIN:
            case HEADERS:
                return true;
            default:
                return false;
        }
    }

    private boolean isSending(RequestState requestState)
    {
        switch (requestState)
        {
            case TRANSIENT_CONTENT:
            case COMMIT:
            case CONTENT:
                return true;
            default:
                return false;
        }
    }

    private RuntimeException illegalSenderState(SenderState current)
    {
        return new IllegalStateException("Expected " + current + " found " + senderState.get() + " instead");
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x(req=%s,snd=%s)",
                getClass().getSimpleName(),
                hashCode(),
                requestState,
                senderState);
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
         * The content transition method is being executed.
         */
        TRANSIENT_CONTENT,
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

    /**
     * The sender states {@link HttpSender} goes through when sending a request.
     */
    private enum SenderState
    {
        /**
         * {@link HttpSender} is not sending request headers nor request content
         */
        IDLE,
        /**
         * {@link HttpSender} is sending the request header or request content
         */
        SENDING,
        /**
         * {@link HttpSender} is currently sending the request, and deferred content is available to be sent
         */
        SENDING_WITH_CONTENT,
        /**
         * {@link HttpSender} is sending the headers but will wait for 100 Continue before sending the content
         */
        EXPECTING,
        /**
         * {@link HttpSender} is currently sending the headers, will wait for 100 Continue, and deferred content is available to be sent
         */
        EXPECTING_WITH_CONTENT,
        /**
         * {@link HttpSender} has sent the headers and is waiting for 100 Continue
         */
        WAITING,
        /**
         * {@link HttpSender} is sending the headers, while 100 Continue has arrived
         */
        PROCEEDING,
        /**
         * {@link HttpSender} is sending the headers, while 100 Continue has arrived, and deferred content is available to be sent
         */
        PROCEEDING_WITH_CONTENT
    }

    private class CommitCallback implements Callback
    {
        @Override
        public void succeeded()
        {
            try
            {
                content.succeeded();
                process();
            }
            catch (Throwable x)
            {
                anyToFailure(x);
            }
        }

        @Override
        public void failed(Throwable failure)
        {
            content.failed(failure);
            anyToFailure(failure);
        }

        private void process() throws Exception
        {
            HttpExchange exchange = getHttpExchange();
            if (exchange == null)
                return;

            Request request = exchange.getRequest();
            if (!headersToCommit(request))
                return;

            HttpContent content = HttpSender.this.content;

            if (!content.hasContent())
            {
                // No content to send, we are done.
                someToSuccess(exchange);
            }
            else
            {
                // Was any content sent while committing ?
                ByteBuffer contentBuffer = content.getContent();
                if (contentBuffer != null)
                {
                    if (!someToContent(request, contentBuffer))
                        return;
                }

                while (true)
                {
                    SenderState current = senderState.get();
                    switch (current)
                    {
                        case SENDING:
                        {
                            contentCallback.iterate();
                            return;
                        }
                        case SENDING_WITH_CONTENT:
                        {
                            // We have deferred content to send.
                            updateSenderState(current, SenderState.SENDING);
                            break;
                        }
                        case EXPECTING:
                        {
                            // We sent the headers, wait for the 100 Continue response.
                            if (updateSenderState(current, SenderState.WAITING))
                                return;
                            break;
                        }
                        case EXPECTING_WITH_CONTENT:
                        {
                            // We sent the headers, we have deferred content to send,
                            // wait for the 100 Continue response.
                            if (updateSenderState(current, SenderState.WAITING))
                                return;
                            break;
                        }
                        case PROCEEDING:
                        {
                            // We sent the headers, we have the 100 Continue response,
                            // we have no content to send.
                            if (updateSenderState(current, SenderState.IDLE))
                                return;
                            break;
                        }
                        case PROCEEDING_WITH_CONTENT:
                        {
                            // We sent the headers, we have the 100 Continue response,
                            // we have deferred content to send.
                            updateSenderState(current, SenderState.SENDING);
                            break;
                        }
                        default:
                        {
                            throw illegalSenderState(current);
                        }
                    }
                }
            }
        }
    }

    private class ContentCallback extends IteratingCallback
    {
        @Override
        protected Action process() throws Exception
        {
            HttpExchange exchange = getHttpExchange();
            if (exchange == null)
                return Action.IDLE;

            HttpContent content = HttpSender.this.content;
            while (true)
            {
                boolean advanced = content.advance();
                boolean consumed = content.isConsumed();
                if (LOG.isDebugEnabled())
                    LOG.debug("Content {} consumed {} for {}", advanced, consumed, exchange.getRequest());

                if (advanced)
                {
                    sendContent(exchange, content, this);
                    return Action.SCHEDULED;
                }

                if (consumed)
                {
                    sendContent(exchange, content, lastCallback);
                    return Action.IDLE;
                }

                SenderState current = HttpSender.this.senderState.get();
                switch (current)
                {
                    case SENDING:
                    {
                        if (updateSenderState(current, SenderState.IDLE))
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("Content is deferred for {}", exchange.getRequest());
                            return Action.IDLE;
                        }
                        break;
                    }
                    case SENDING_WITH_CONTENT:
                    {
                        updateSenderState(current, SenderState.SENDING);
                        break;
                    }
                    default:
                    {
                        throw illegalSenderState(current);
                    }
                }
            }
        }

        @Override
        public void succeeded()
        {
            ByteBuffer buffer = content.getContent();
            someToContent(getHttpExchange().getRequest(), buffer);
            content.succeeded();
            super.succeeded();
        }

        @Override
        public void onCompleteFailure(Throwable failure)
        {
            content.failed(failure);
            anyToFailure(failure);
        }

        @Override
        protected void onCompleteSuccess()
        {
            // Nothing to do, since we always return false from process().
            // Termination is obtained via LastContentCallback.
        }
    }

    private class LastContentCallback implements Callback
    {
        @Override
        public void succeeded()
        {
            content.succeeded();
            HttpExchange exchange = getHttpExchange();
            if (exchange == null)
                return;
            someToSuccess(exchange);
        }

        @Override
        public void failed(Throwable failure)
        {
            content.failed(failure);
            anyToFailure(failure);
        }
    }
}
