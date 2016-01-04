//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.util.List;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpExchange
{
    private static final Logger LOG = Log.getLogger(HttpExchange.class);

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
        synchronized (this)
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
        synchronized (this)
        {
            return responseFailure;
        }
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
        synchronized (this)
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
        synchronized (this)
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
        synchronized (this)
        {
            return _channel;
        }
    }

    public boolean requestComplete(Throwable failure)
    {
        synchronized (this)
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

    public boolean responseComplete(Throwable failure)
    {
        synchronized (this)
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
        synchronized (this)
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
        synchronized (this)
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

    public boolean abort(Throwable failure)
    {
        // Atomically change the state of this exchange to be completed.
        // This will avoid that this exchange can be associated to a channel.
        boolean abortRequest;
        boolean abortResponse;
        synchronized (this)
        {
            abortRequest = completeRequest(failure);
            abortResponse = completeResponse(failure);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Failed {}: req={}/rsp={} {}", this, abortRequest, abortResponse, failure);

        if (!abortRequest && !abortResponse)
            return false;

        // We failed this exchange, deal with it.

        // Case #1: exchange was in the destination queue.
        if (destination.remove(this))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Aborting while queued {}: {}", this, failure);
            notifyFailureComplete(failure);
            return true;
        }

        HttpChannel channel = getHttpChannel();
        if (channel == null)
        {
            // Case #2: exchange was not yet associated.
            // Because this exchange is failed, when associate() is called
            // it will return false, and the caller will dispose the channel.
            if (LOG.isDebugEnabled())
                LOG.debug("Aborted before association {}: {}", this, failure);
            notifyFailureComplete(failure);
            return true;
        }

        // Case #3: exchange was already associated.
        boolean aborted = channel.abort(this, abortRequest ? failure : null, abortResponse ? failure : null);
        if (LOG.isDebugEnabled())
            LOG.debug("Aborted ({}) while active {}: {}", aborted, this, failure);
        return aborted;
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
        synchronized (this)
        {
            responseState = State.PENDING;
            responseFailure = null;
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
        synchronized (this)
        {
            return String.format("%s@%x req=%s/%s@%h res=%s/%s@%h",
                    HttpExchange.class.getSimpleName(),
                    hashCode(),
                    requestState, requestFailure, requestFailure,
                    responseState, responseFailure, responseFailure);
        }
    }

    private enum State
    {
        PENDING, COMPLETED, TERMINATED
    }
}
