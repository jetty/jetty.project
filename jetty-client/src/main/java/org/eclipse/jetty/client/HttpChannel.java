//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client;

import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class HttpChannel
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpChannel.class);

    private final AutoLock _lock = new AutoLock();
    private final HttpDestination _destination;
    private final TimeoutCompleteListener _totalTimeout;
    private HttpExchange _exchange;

    protected HttpChannel(HttpDestination destination)
    {
        _destination = destination;
        _totalTimeout = new TimeoutCompleteListener(destination.getHttpClient().getScheduler());
    }

    public void destroy()
    {
        _totalTimeout.destroy();
    }

    public HttpDestination getHttpDestination()
    {
        return _destination;
    }

    /**
     * <p>Associates the given {@code exchange} to this channel in order to be sent over the network.</p>
     * <p>If the association is successful, the exchange can be sent. Otherwise, the channel must be
     * disposed because whoever terminated the exchange did not do it - it did not have the channel yet.</p>
     *
     * @param exchange the exchange to associate
     * @return true if the association was successful, false otherwise
     */
    public boolean associate(HttpExchange exchange)
    {
        boolean result = false;
        boolean abort = true;
        try (AutoLock l = _lock.lock())
        {
            if (_exchange == null)
            {
                abort = false;
                result = exchange.associate(this);
                if (result)
                    _exchange = exchange;
            }
        }

        if (abort)
        {
            exchange.getRequest().abort(new UnsupportedOperationException("Pipelined requests not supported"));
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} associated {} to {}", exchange, result, this);
        }

        return result;
    }

    public boolean disassociate(HttpExchange exchange)
    {
        boolean result = false;
        try (AutoLock l = _lock.lock())
        {
            HttpExchange existing = _exchange;
            _exchange = null;
            if (existing == exchange)
            {
                existing.disassociate(this);
                result = true;
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("{} disassociated {} from {}", exchange, result, this);
        return result;
    }

    public HttpExchange getHttpExchange()
    {
        try (AutoLock l = _lock.lock())
        {
            return _exchange;
        }
    }

    protected abstract HttpSender getHttpSender();

    protected abstract HttpReceiver getHttpReceiver();

    public void send()
    {
        HttpExchange exchange = getHttpExchange();
        if (exchange != null)
        {
            HttpRequest request = exchange.getRequest();
            long timeoutAt = request.getTimeoutAt();
            if (timeoutAt != -1)
            {
                exchange.getResponseListeners().add(_totalTimeout);
                _totalTimeout.schedule(request, timeoutAt);
            }
            send(exchange);
        }
    }

    public abstract void send(HttpExchange exchange);

    public abstract void release();

    public void proceed(HttpExchange exchange, Throwable failure)
    {
        getHttpSender().proceed(exchange, failure);
    }

    public boolean abort(HttpExchange exchange, Throwable requestFailure, Throwable responseFailure)
    {
        boolean requestAborted = false;
        if (requestFailure != null)
            requestAborted = getHttpSender().abort(exchange, requestFailure);

        boolean responseAborted = false;
        if (responseFailure != null)
            responseAborted = abortResponse(exchange, responseFailure);

        return requestAborted || responseAborted;
    }

    public boolean abortResponse(HttpExchange exchange, Throwable failure)
    {
        return getHttpReceiver().abort(exchange, failure);
    }

    public Result exchangeTerminating(HttpExchange exchange, Result result)
    {
        return result;
    }

    public void exchangeTerminated(HttpExchange exchange, Result result)
    {
        disassociate(exchange);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x(exchange=%s)", getClass().getSimpleName(), hashCode(), getHttpExchange());
    }
}
