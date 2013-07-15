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
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public abstract class HttpSender implements AsyncContentProvider.Listener
{
    protected static final Logger LOG = Log.getLogger(new Object(){}.getClass().getEnclosingClass());

    private final AtomicReference<RequestState> requestState = new AtomicReference<>(RequestState.QUEUED);
    private final AtomicReference<SenderState> senderState = new AtomicReference<>(SenderState.IDLE);
    private final HttpChannel channel;
    private volatile HttpContent content;

    public HttpSender(HttpChannel channel)
    {
        this.channel = channel;
    }

    public HttpChannel getHttpChannel()
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
                    if (updateSenderState(current, SenderState.SENDING))
                    {
                        LOG.debug("Deferred content available, idle -> sending");
                        content.advance();
                        sendContent(exchange, new ContentCallback(content));
                        return;
                    }
                    break;
                }
                case SENDING:
                {
                    if (updateSenderState(current, SenderState.SCHEDULED))
                    {
                        LOG.debug("Deferred content available, sending -> scheduled");
                        return;
                    }
                    break;
                }
                case EXPECTING:
                {
                    if (updateSenderState(current, SenderState.SCHEDULED))
                    {
                        LOG.debug("Deferred content available, expecting -> scheduled");
                        return;
                    }
                    break;
                }
                case WAITING:
                {
                    LOG.debug("Deferred content available, waiting");
                    return;
                }
                case SCHEDULED:
                {
                    LOG.debug("Deferred content available, scheduled");
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
        Request request = exchange.getRequest();
        Throwable cause = request.getAbortCause();
        if (cause != null)
        {
            exchange.abort(cause);
        }
        else
        {
            if (!queuedToBegin(request))
                throw new IllegalStateException();

            if (!updateSenderState(SenderState.IDLE, expects100Continue(request) ? SenderState.EXPECTING : SenderState.SENDING))
                throw new IllegalStateException();

            ContentProvider contentProvider = request.getContent();
            HttpContent content = this.content = new CommitCallback(contentProvider);

            // Setting the listener may trigger calls to onContent() by other
            // threads so we must set it only after the sender state has been updated
            if (contentProvider instanceof AsyncContentProvider)
                ((AsyncContentProvider)contentProvider).setListener(this);

            if (!beginToHeaders(request))
                return;

            sendHeaders(exchange, content);
        }
    }

    protected boolean expects100Continue(Request request)
    {
        return request.getHeaders().contains(HttpHeader.EXPECT, HttpHeaderValue.CONTINUE.asString());
    }

    protected boolean queuedToBegin(Request request)
    {
        if (!updateRequestState(RequestState.QUEUED, RequestState.BEGIN))
            return false;
        LOG.debug("Request begin {}", request);
        RequestNotifier notifier = getHttpChannel().getHttpDestination().getRequestNotifier();
        notifier.notifyBegin(request);
        return true;
    }

    protected boolean beginToHeaders(Request request)
    {
        if (!updateRequestState(RequestState.BEGIN, RequestState.HEADERS))
            return false;
        if (LOG.isDebugEnabled())
            LOG.debug("Request headers {}{}{}", request, System.getProperty("line.separator"), request.getHeaders().toString().trim());
        RequestNotifier notifier = getHttpChannel().getHttpDestination().getRequestNotifier();
        notifier.notifyHeaders(request);
        return true;
    }

    protected boolean headersToCommit(Request request)
    {
        if (!updateRequestState(RequestState.HEADERS, RequestState.COMMIT))
            return false;
        LOG.debug("Request committed {}", request);
        RequestNotifier notifier = getHttpChannel().getHttpDestination().getRequestNotifier();
        notifier.notifyCommit(request);
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
                if (!updateRequestState(current, RequestState.CONTENT))
                    return false;
                if (LOG.isDebugEnabled())
                    LOG.debug("Request content {}{}{}", request, System.getProperty("line.separator"), BufferUtil.toDetailString(content));
                RequestNotifier notifier = getHttpChannel().getHttpDestination().getRequestNotifier();
                notifier.notifyContent(request, content);
                return true;
            }
            case FAILURE:
            {
                return false;
            }
            default:
            {
                throw new IllegalStateException();
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

                // Reset to be ready for another request
                reset();

                // Mark atomically the request as terminated and succeeded,
                // with respect to concurrency between request and response.
                Result result = exchange.terminateRequest(null);

                // It is important to notify completion *after* we reset because
                // the notification may trigger another request/response
                Request request = exchange.getRequest();
                LOG.debug("Request success {}", request);
                HttpDestination destination = getHttpChannel().getHttpDestination();
                destination.getRequestNotifier().notifySuccess(exchange.getRequest());

                if (result != null)
                {
                    boolean ordered = destination.getHttpClient().isStrictEventOrdering();
                    if (!ordered)
                        channel.exchangeTerminated(result);
                    LOG.debug("Request/Response succeded {}", request);
                    HttpConversation conversation = exchange.getConversation();
                    destination.getResponseNotifier().notifyComplete(conversation.getResponseListeners(), result);
                    if (ordered)
                        channel.exchangeTerminated(result);
                }

                return true;
            }
            case FAILURE:
            {
                return false;
            }
            default:
            {
                throw new IllegalStateException();
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

        // Dispose to avoid further requests
        RequestState requestState = dispose();

        // Mark atomically the request as terminated and failed,
        // with respect to concurrency between request and response.
        Result result = exchange.terminateRequest(failure);

        Request request = exchange.getRequest();
        LOG.debug("Request failure {} {}", exchange, failure);
        HttpDestination destination = getHttpChannel().getHttpDestination();
        destination.getRequestNotifier().notifyFailure(request, failure);

        boolean notCommitted = isBeforeCommit(requestState);
        if (result == null && notCommitted && request.getAbortCause() == null)
        {
            // Complete the response from here
            exchange.responseComplete();
            result = exchange.terminateResponse(failure);
            LOG.debug("Failed response from request {}", exchange);
        }

        if (result != null)
        {
            boolean ordered = destination.getHttpClient().isStrictEventOrdering();
            if (!ordered)
                channel.exchangeTerminated(result);
            LOG.debug("Request/Response failed {}", request);
            HttpConversation conversation = exchange.getConversation();
            destination.getResponseNotifier().notifyComplete(conversation.getResponseListeners(), result);
            if (ordered)
                channel.exchangeTerminated(result);
        }

        return true;
    }

    protected abstract void sendHeaders(HttpExchange exchange, HttpContent content);

    protected abstract void sendContent(HttpExchange exchange, HttpContent content);

    protected void reset()
    {
        requestState.set(RequestState.QUEUED);
        senderState.set(SenderState.IDLE);
        content = null;
    }

    protected RequestState dispose()
    {
        while (true)
        {
            RequestState current = requestState.get();
            if (updateRequestState(current, RequestState.FAILURE))
                return current;
        }
    }

    public void proceed(HttpExchange exchange, boolean proceed)
    {
        if (!expects100Continue(exchange.getRequest()))
            return;

        if (proceed)
        {
            while (true)
            {
                SenderState current = senderState.get();
                switch (current)
                {
                    case EXPECTING:
                    {
                        // We are still sending the headers, but we already got the 100 Continue.
                        // Move to SEND so that the commit callback can send the content.
                        if (!updateSenderState(current, SenderState.SENDING))
                            break;
                        LOG.debug("Proceed while expecting");
                        return;
                    }
                    case WAITING:
                    {
                        // We received the 100 Continue, send the content if any
                        // First update the sender state to be sure to be the one
                        // to call sendContent() since we race with onContent().
                        if (!updateSenderState(current, SenderState.SENDING))
                            break;
                        if (content.advance())
                        {
                            // There is content to send
                            LOG.debug("Proceed while waiting");
                            sendContent(exchange, new ContentCallback(content));
                        }
                        else
                        {
                            // No content to send yet - it's deferred.
                            // We may fail the update as onContent() moved to SCHEDULE.
                            if (!updateSenderState(SenderState.SENDING, SenderState.IDLE))
                                break;
                            LOG.debug("Proceed deferred");
                        }
                        return;
                    }
                    case SCHEDULED:
                    {
                        // We lost the race with onContent() to update the state, try again
                        if (!updateSenderState(current, SenderState.WAITING))
                            throw new IllegalStateException();
                        LOG.debug("Proceed while scheduled");
                        break;
                    }
                    default:
                    {
                        throw new IllegalStateException();
                    }
                }
            }
        }
        else
        {
            anyToFailure(new HttpRequestException("Expectation failed", exchange.getRequest()));
        }
    }

    public boolean abort(Throwable failure)
    {
        RequestState current = requestState.get();
        boolean abortable = isBeforeCommit(current) ||
                isSending(current) && !content.isLast();
        return abortable && anyToFailure(failure);
    }

    protected boolean updateRequestState(RequestState from, RequestState to)
    {
        boolean updated = requestState.compareAndSet(from, to);
        if (!updated)
            LOG.debug("RequestState update failed: {} -> {}: {}", from, to, requestState.get());
        return updated;
    }

    protected boolean updateSenderState(SenderState from, SenderState to)
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
            case COMMIT:
            case CONTENT:
                return true;
            default:
                return false;
        }
    }

    protected enum RequestState
    {
        QUEUED, BEGIN, HEADERS, COMMIT, CONTENT, FAILURE
    }

    protected enum SenderState
    {
        IDLE, SENDING, EXPECTING, WAITING, SCHEDULED
    }

    private class CommitCallback extends HttpContent
    {
        private CommitCallback(ContentProvider contentProvider)
        {
            super(contentProvider);
        }

        @Override
        public void succeeded()
        {
            try
            {
                process();
            }
            catch (Exception x)
            {
                anyToFailure(x);
            }
        }

        private void process() throws Exception
        {
            HttpExchange exchange = getHttpExchange();
            if (exchange == null)
                return;

            Request request = exchange.getRequest();
            if (!headersToCommit(request))
                return;

            if (!hasContent())
            {
                // No content to send, we are done.
                someToSuccess(exchange);
            }
            else
            {
                // Was any content sent while committing ?
                ByteBuffer content = getContent();
                if (content != null)
                {
                    if (!someToContent(request, content))
                        return;
                }

                while (true)
                {
                    SenderState current = senderState.get();
                    switch (current)
                    {
                        case SENDING:
                        {
                            // We have content to send ?
                            if (advance())
                            {
                                sendContent(exchange, new ContentCallback(this));
                            }
                            else
                            {
                                if (isLast())
                                {
                                    sendContent(exchange, new LastContentCallback(this));
                                }
                                else
                                {
                                    if (!updateSenderState(current, SenderState.IDLE))
                                        break;
                                    LOG.debug("Waiting for deferred content for {}", request);
                                }
                            }
                            return;
                        }
                        case EXPECTING:
                        {
                            // Wait for the 100 Continue response
                            if (!updateSenderState(current, SenderState.WAITING))
                                break;
                            return;
                        }
                        case SCHEDULED:
                        {
                            if (expects100Continue(request))
                                return;
                            // We have deferred content to send.
                            updateSenderState(current, SenderState.SENDING);
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

        @Override
        public void failed(Throwable failure)
        {
            anyToFailure(failure);
        }
    }

    private class ContentCallback extends HttpContent
    {
        private final IteratingCallback delegate = new Delegate(this);

        public ContentCallback(HttpContent content)
        {
            super(content);
        }

        @Override
        public void succeeded()
        {
            delegate.succeeded();
        }

        @Override
        public void failed(Throwable failure)
        {
            anyToFailure(failure);
        }

        private class Delegate extends IteratingNestedCallback
        {
            private Delegate(Callback callback)
            {
                super(callback);
            }

            @Override
            protected boolean process() throws Exception
            {
                HttpExchange exchange = getHttpExchange();
                if (exchange == null)
                    return false;

                Request request = exchange.getRequest();

                ByteBuffer contentBuffer = getContent();
                if (contentBuffer != null)
                {
                    if (!someToContent(request, contentBuffer))
                        return false;
                }

                if (advance())
                {
                    // There is more content to send
                    sendContent(exchange, ContentCallback.this);
                }
                else
                {
                    if (isLast())
                    {
                        sendContent(exchange, new LastContentCallback(ContentCallback.this));
                    }
                    else
                    {
                        while (true)
                        {
                            SenderState current = senderState.get();
                            switch (current)
                            {
                                case SENDING:
                                {
                                    if (updateSenderState(current, SenderState.IDLE))
                                    {
                                        LOG.debug("Waiting for deferred content for {}", request);
                                        return false;
                                    }
                                    break;
                                }
                                case SCHEDULED:
                                {
                                    if (updateSenderState(current, SenderState.SENDING))
                                    {
                                        LOG.debug("Deferred content available for {}", request);
                                        // TODO: this case is not covered by tests
                                        sendContent(exchange, ContentCallback.this);
                                        return false;
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
                return false;
            }
        }
    }

    private class LastContentCallback extends HttpContent
    {
        private LastContentCallback(HttpContent content)
        {
            super(content);
        }

        @Override
        public void succeeded()
        {
            HttpExchange exchange = getHttpExchange();
            if (exchange == null)
                return;
            someToSuccess(exchange);
        }

        @Override
        public void failed(Throwable failure)
        {
            anyToFailure(failure);
        }
    }
}
