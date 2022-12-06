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

package org.eclipse.jetty.client;

import java.util.List;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
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
    private final List<Response.ResponseListener> listeners;
    private final HttpResponse response;
    private State requestState = State.PENDING;
    private State responseState = State.PENDING;
    private HttpChannel _channel;
    private Throwable requestFailure;
    private Throwable responseFailure;

    public HttpExchange(HttpDestination destination, HttpRequest request, List<Response.ResponseListener> listeners)
    {
        this.destination = destination;
        this.request = request;
        this.listeners = listeners;
        this.response = new HttpResponse(request, listeners);
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
        try (AutoLock l = lock.lock())
        {
            return requestFailure;
        }
    }

    public List<Response.ResponseListener> getResponseListeners()
    {
        return listeners;
    }

    public HttpResponse getResponse()
    {
        return response;
    }

    public Throwable getResponseFailure()
    {
        try (AutoLock l = lock.lock())
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
        try (AutoLock l = lock.lock())
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
        try (AutoLock l = lock.lock())
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
        try (AutoLock l = lock.lock())
        {
            return _channel;
        }
    }

    public boolean requestComplete(Throwable failure)
    {
        try (AutoLock l = lock.lock())
        {
            return completeRequest(failure);
        }
    }

    private boolean completeRequest(Throwable failure)
    {
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
        try (AutoLock l = lock.lock())
        {
            return responseState == State.COMPLETED;
        }
    }

    public boolean responseComplete(Throwable failure)
    {
        try (AutoLock l = lock.lock())
        {
            return completeResponse(failure);
        }
    }

    private boolean completeResponse(Throwable failure)
    {
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
        try (AutoLock l = lock.lock())
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
        try (AutoLock l = lock.lock())
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

    public void abort(Throwable failure, Promise<Boolean> promise)
    {
        // Atomically change the state of this exchange to be completed.
        // This will avoid that this exchange can be associated to a channel.
        boolean abortRequest;
        boolean abortResponse;
        try (AutoLock l = lock.lock())
        {
            abortRequest = completeRequest(failure);
            abortResponse = completeResponse(failure);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Failed {}: req={}/rsp={}", this, abortRequest, abortResponse, failure);

        if (!abortRequest && !abortResponse)
        {
            promise.succeeded(false);
            return;
        }

        // We failed this exchange, deal with it.

        // Applications could be blocked providing
        // request content, notify them of the failure.
        Request.Content body = request.getBody();
        if (abortRequest && body != null)
            body.fail(failure);

        // Case #1: exchange was in the destination queue.
        if (destination.remove(this))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Aborting while queued {}: {}", this, failure);
            notifyFailureComplete(failure);
            promise.succeeded(true);
            return;
        }

        HttpChannel channel = getHttpChannel();
        if (channel == null)
        {
            // Case #2: exchange was not yet associated.
            // Because this exchange is failed, when associate() is called
            // it will return false, and the caller will dispose the channel.
            if (LOG.isDebugEnabled())
                LOG.debug("Aborting before association {}: {}", this, failure);
            notifyFailureComplete(failure);
            promise.succeeded(true);
            return;
        }

        // Case #3: exchange was already associated.
        if (LOG.isDebugEnabled())
            LOG.debug("Aborting while active {}: {}", this, failure);
        channel.abort(this, abortRequest ? failure : null, abortResponse ? failure : null, promise);
    }

    private void notifyFailureComplete(Throwable failure)
    {
        destination.getRequestNotifier().notifyFailure(request, failure);
        List<Response.ResponseListener> listeners = getConversation().getResponseListeners();
        ResponseNotifier responseNotifier = destination.getResponseNotifier();
        responseNotifier.notifyFailure(listeners, response, failure);
        responseNotifier.notifyComplete(listeners, new Result(request, failure, response, failure));
    }

    public void resetResponse()
    {
        try (AutoLock l = lock.lock())
        {
            responseState = State.PENDING;
            responseFailure = null;
            response.clearHeaders();
        }
    }

    public void proceed(Throwable failure)
    {
        HttpChannel channel = getHttpChannel();
        if (channel != null)
            channel.proceed(this, failure);
    }

    @Override
    public String toString()
    {
        try (AutoLock l = lock.lock())
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
