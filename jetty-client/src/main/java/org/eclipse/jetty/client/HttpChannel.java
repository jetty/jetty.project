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

import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public abstract class HttpChannel
{
    protected static final Logger LOG = Log.getLogger(HttpChannel.class);

    private final HttpDestination _destination;
    private HttpExchange _exchange;

    protected HttpChannel(HttpDestination destination)
    {
        this._destination = destination;
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
        synchronized (this)
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
            exchange.getRequest().abort(new UnsupportedOperationException("Pipelined requests not supported"));

        if (LOG.isDebugEnabled())
            LOG.debug("{} associated {} to {}", exchange, result, this);

        return result;
    }

    public boolean disassociate(HttpExchange exchange)
    {
        boolean result = false;
        synchronized (this)
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
        synchronized (this)
        {
            return _exchange;
        }
    }

    protected abstract HttpSender getHttpSender();

    protected abstract HttpReceiver getHttpReceiver();

    public abstract void send();

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
