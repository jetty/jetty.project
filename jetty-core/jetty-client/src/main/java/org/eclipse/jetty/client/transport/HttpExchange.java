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

import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Result;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpExchange implements CyclicTimeouts.Expirable
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpExchange.class);

    private final AutoLock lock = new AutoLock();
    private final HttpDestination destination;
    private final HttpRequest request;
    private final ResponseListeners listeners;
    private final HttpResponse response;
    private State requestState = State.PENDING;
    private State responseState = State.PENDING;
    private HttpChannel _channel;
    private Throwable requestFailure;
    private Throwable responseFailure;

    public HttpExchange(HttpDestination destination, HttpRequest request)
    {
        this(destination, request, request.getResponseListeners());
    }

    public HttpExchange(HttpDestination destination, HttpRequest request, ResponseListeners listeners)
    {
        this.destination = destination;
        this.request = request;
        this.listeners = listeners;
        this.response = new HttpResponse(request);
        HttpConversation conversation = request.getConversation();
        conversation.getExchanges().offer(this);
        conversation.updateResponseListeners(null);
    }

    public HttpDestination getHttpDestination()
    {
        return destination;
    }

    public HttpConversation getConversation()
    {
        return request.getConversation();
    }

    public HttpRequest getRequest()
    {
        return request;
    }

    public Throwable getRequestFailure()
    {
        try (AutoLock ignored = lock.lock())
        {
            return requestFailure;
        }
    }

    public ResponseListeners getResponseListeners()
    {
        return listeners;
    }

    public HttpResponse getResponse()
    {
        return response;
    }

    public Throwable getResponseFailure()
    {
        try (AutoLock ignored = lock.lock())
        {
            return responseFailure;
        }
    }

    @Override
    public long getExpireNanoTime()
    {
        return request.getTimeoutNanoTime();
    }

    /**
     * <p>Associates the given {@code channel} to this exchange.</p>
     * <p>Works in strict collaboration with {@link HttpChannel#associate(HttpExchange)}.</p>
     *
     * @param channel the channel to associate to this exchange
     * @return true if the channel could be associated, false otherwise
     */
    boolean associate(HttpChannel channel)
    {
        boolean result = false;
        boolean abort = false;
        try (AutoLock ignored = lock.lock())
        {
            // Only associate if the exchange state is initial,
            // as the exchange could be already failed.
            if (requestState == State.PENDING && responseState == State.PENDING)
            {
                abort = _channel != null;
                if (!abort)
                {
                    _channel = channel;
                    result = true;
                }
            }
        }

        if (abort)
            request.abort(new IllegalStateException(toString()));

        return result;
    }

    void disassociate(HttpChannel channel)
    {
        boolean abort = false;
        try (AutoLock ignored = lock.lock())
        {
            if (_channel != channel || requestState != State.TERMINATED || responseState != State.TERMINATED)
                abort = true;
            _channel = null;
        }

        if (abort)
            request.abort(new IllegalStateException(toString()));
    }

    private HttpChannel getHttpChannel()
    {
        try (AutoLock ignored = lock.lock())
        {
            return _channel;
        }
    }

    public boolean requestComplete(Throwable failure)
    {
        try (AutoLock ignored = lock.lock())
        {
            return lockedCompleteRequest(failure);
        }
    }

    private boolean lockedCompleteRequest(Throwable failure)
    {
        assert lock.isHeldByCurrentThread();
        if (requestState == State.PENDING)
        {
            requestState = State.COMPLETED;
            requestFailure = failure;
            return true;
        }
        return false;
    }

    public boolean isResponseComplete()
    {
        try (AutoLock ignored = lock.lock())
        {
            return responseState == State.COMPLETED;
        }
    }

    public boolean responseComplete(Throwable failure)
    {
        try (AutoLock ignored = lock.lock())
        {
            return lockedCompleteResponse(failure);
        }
    }

    private boolean lockedCompleteResponse(Throwable failure)
    {
        assert lock.isHeldByCurrentThread();
        if (responseState == State.PENDING)
        {
            responseState = State.COMPLETED;
            responseFailure = failure;
            return true;
        }
        return false;
    }

    public Result terminateRequest()
    {
        Result result = null;
        try (AutoLock ignored = lock.lock())
        {
            if (requestState == State.COMPLETED)
                requestState = State.TERMINATED;
            if (requestState == State.TERMINATED && responseState == State.TERMINATED)
                result = new Result(getRequest(), requestFailure, getResponse(), responseFailure);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Terminated request for {}, result: {}", this, result);

        return result;
    }

    public Result terminateResponse()
    {
        Result result = null;
        try (AutoLock ignored = lock.lock())
        {
            if (responseState == State.COMPLETED)
                responseState = State.TERMINATED;
            if (requestState == State.TERMINATED && responseState == State.TERMINATED)
                result = new Result(getRequest(), requestFailure, getResponse(), responseFailure);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Terminated response for {}, result: {}", this, result);

        return result;
    }

    boolean isResponseCompleteOrTerminated()
    {
        try (AutoLock ignored = lock.lock())
        {
            return responseState == State.COMPLETED || responseState == State.TERMINATED;
        }
    }

    public void abort(Throwable failure, Promise<Boolean> promise)
    {
        // Atomically change the state of this exchange to be completed.
        // This will avoid that this exchange can be associated to a channel.
        HttpChannel channel;
        boolean abortRequest;
        boolean abortResponse;
        try (AutoLock ignored = lock.lock())
        {
            channel = _channel;
            abortRequest = lockedCompleteRequest(failure);
            abortResponse = lockedCompleteResponse(failure);
        }

        if (!abortRequest && !abortResponse)
        {
            promise.succeeded(false);
            return;
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Failed {}: req={}/rsp={}", this, abortRequest, abortResponse, failure);

        // We failed this exchange, deal with it.

        // Applications could be blocked providing
        // request content, notify them of the failure.
        Request.Content body = request.getBody();
        if (abortRequest && body != null)
        {
            // This may eventually complete the request,
            // and if the response is already completed
            // also invoke the Response.CompleteListeners.
            body.fail(failure);
        }

        // Case #1: exchange was in the destination queue.
        if (destination.remove(this))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Aborting while queued {}", this, failure);
            notifyFailureComplete(failure);
            promise.succeeded(true);
            return;
        }

        if (channel == null && abortRequest)
        {
            // Case #2: exchange was not yet associated.
            if (LOG.isDebugEnabled())
                LOG.debug("Aborting before association {}", this, failure);
            notifyFailureComplete(failure);
            promise.succeeded(true);
            return;
        }

        // Case #3: exchange was already associated.
        if (LOG.isDebugEnabled())
            LOG.debug("Aborting while active {}", this, failure);
        channel.abort(this, abortRequest ? failure : null, abortResponse ? failure : null, promise);
    }

    private void notifyFailureComplete(Throwable failure)
    {
        request.notifyFailure(failure);
        ResponseListeners listeners = getConversation().getResponseListeners();
        listeners.notifyFailure(response, failure);
        listeners.notifyComplete(new Result(request, failure, response, failure));
    }

    public void resetResponse()
    {
        try (AutoLock ignored = lock.lock())
        {
            responseState = State.PENDING;
            responseFailure = null;
            response.clearHeaders();
        }
    }

    public void proceed(Runnable proceedAction, Throwable failure)
    {
        HttpChannel channel = getHttpChannel();
        if (channel != null)
            channel.proceed(this, proceedAction, failure);
    }

    @Override
    public String toString()
    {
        try (AutoLock ignored = lock.lock())
        {
            return String.format("%s@%x{req=%s[%s/%s] res=%s[%s/%s]}",
                HttpExchange.class.getSimpleName(),
                hashCode(),
                request, requestState, requestFailure,
                response, responseState, responseFailure);
        }
    }

    private enum State
    {
        PENDING, COMPLETED, TERMINATED
    }
}
