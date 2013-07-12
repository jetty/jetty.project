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

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public abstract class HttpChannel
{
    protected static final Logger LOG = Log.getLogger(new Object(){}.getClass().getEnclosingClass());

    private final AtomicReference<HttpExchange> exchange = new AtomicReference<>();
    private final HttpDestination destination;

    protected HttpChannel(HttpDestination destination)
    {
        this.destination = destination;
    }

    public HttpDestination getHttpDestination()
    {
        return destination;
    }

    public void associate(HttpExchange exchange)
    {
        if (!this.exchange.compareAndSet(null, exchange))
            throw new UnsupportedOperationException("Pipelined requests not supported");
        exchange.associate(this);
        LOG.debug("{} associated to {}", exchange, this);
    }

    public HttpExchange disassociate()
    {
        HttpExchange exchange = this.exchange.getAndSet(null);
        if (exchange != null)
            exchange.disassociate(this);
        LOG.debug("{} disassociated from {}", exchange, this);
        return exchange;
    }

    public HttpExchange getHttpExchange()
    {
        return exchange.get();
    }

    public abstract void send();

    public abstract void proceed(HttpExchange exchange, boolean proceed);

    public abstract boolean abort(Throwable cause);

    public void exchangeTerminated(Result result)
    {
        disassociate();
    }
}
