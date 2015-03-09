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

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.SpinLock;

public class HttpExchange
{
    private static final Logger LOG = Log.getLogger(HttpExchange.class);

    private final AtomicReference<HttpChannel> channel = new AtomicReference<>();
    private final HttpDestination destination;
    private final HttpRequest request;
    private final List<Response.ResponseListener> listeners;
    private final HttpResponse response;
    
    enum State { PENDING, COMPLETED, TERMINATED } ;
    private final SpinLock _lock = new SpinLock();
    private State requestState=State.PENDING;
    private State responseState=State.PENDING;
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
        try(SpinLock.Lock lock = _lock.lock())
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
        try(SpinLock.Lock lock = _lock.lock())
        {
            return responseFailure;
        }
    }

    public void associate(HttpChannel channel)
    {
        if (!this.channel.compareAndSet(null, channel))
            throw new IllegalStateException();
    }

    public void disassociate(HttpChannel channel)
    {
        if (!this.channel.compareAndSet(channel, null))
            throw new IllegalStateException();
    }

    public boolean requestComplete()
    {
        try(SpinLock.Lock lock = _lock.lock())
        {
            if (requestState!=State.PENDING)
                return false;
            requestState=State.COMPLETED;
            return true;
        }
    }

    public boolean responseComplete()
    {
        try(SpinLock.Lock lock = _lock.lock())
        {
            if (responseState!=State.PENDING)
                return false;
            responseState=State.COMPLETED;
            return true;
        }
    }

    public Result terminateRequest(Throwable failure)
    {
        try(SpinLock.Lock lock = _lock.lock())
        {
            requestState=State.TERMINATED;
            requestFailure=failure;
            if (State.TERMINATED.equals(responseState))
                return new Result(getRequest(), requestFailure, getResponse(), responseFailure);
        }
        return null;
    }

    public Result terminateResponse(Throwable failure)
    {
        try(SpinLock.Lock lock = _lock.lock())
        {
            responseState=State.TERMINATED;
            responseFailure=failure;
            if (State.TERMINATED.equals(requestState))
                return new Result(getRequest(), requestFailure, getResponse(), responseFailure);
        }
        return null;
    }


    public boolean abort(Throwable cause)
    {
        if (destination.remove(this))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Aborting while queued {}: {}", this, cause);
            return fail(cause);
        }
        else
        {
            HttpChannel channel = this.channel.get();
            if (channel == null)
                return fail(cause);

            boolean aborted = channel.abort(cause);
            if (LOG.isDebugEnabled())
                LOG.debug("Aborted ({}) while active {}: {}", aborted, this, cause);
            return aborted;
        }
    }

    private boolean fail(Throwable cause)
    {
        boolean notify=false;
        try(SpinLock.Lock lock = _lock.lock())
        {
            if (!Boolean.TRUE.equals(requestState))
            {
                requestState=State.TERMINATED;
                notify=true;
                requestFailure=cause;
            }
            if (!Boolean.TRUE.equals(responseState))
            {
                responseState=State.TERMINATED;
                notify=true;
                responseFailure=cause;
            }
        }
        
        if (notify)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Failing {}: {}", this, cause);
            destination.getRequestNotifier().notifyFailure(request, cause);
            List<Response.ResponseListener> listeners = getConversation().getResponseListeners();
            ResponseNotifier responseNotifier = destination.getResponseNotifier();
            responseNotifier.notifyFailure(listeners, response, cause);
            responseNotifier.notifyComplete(listeners, new Result(request, cause, response, cause));
            return true;
        }
        else
        {
            return false;
        }
    }

    public void resetResponse()
    {
        try(SpinLock.Lock lock = _lock.lock())
        {
            responseState=State.PENDING;
            responseFailure=null;
        }
    }

    public void proceed(Throwable failure)
    {
        HttpChannel channel = this.channel.get();
        if (channel != null)
            channel.proceed(this, failure);
    }

    @Override
    public String toString()
    {
        try(SpinLock.Lock lock = _lock.lock())
        {
            return String.format("%s@%x req=%s/%s res=%s/%s",
                HttpExchange.class.getSimpleName(),
                hashCode(),
                requestState,requestFailure,
                responseState,responseFailure);
        }
    }
}
