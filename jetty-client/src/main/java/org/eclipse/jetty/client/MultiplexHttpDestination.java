//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.util.Promise;

public abstract class MultiplexHttpDestination<C extends Connection> extends HttpDestination implements Promise<Connection>
{
    private final AtomicReference<ConnectState> connect = new AtomicReference<>(ConnectState.DISCONNECTED);
    private C connection;

    protected MultiplexHttpDestination(HttpClient client, Origin origin)
    {
        super(client, origin);
    }

    @Override
    protected void send()
    {
        while (true)
        {
            ConnectState current = connect.get();
            switch (current)
            {
                case DISCONNECTED:
                {
                    if (!connect.compareAndSet(current, ConnectState.CONNECTING))
                        break;
                    newConnection(this);
                    return;
                }
                case CONNECTING:
                {
                    // Waiting to connect, just return
                    return;
                }
                case CONNECTED:
                {
                    if (process(connection, false))
                        break;
                    return;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void succeeded(Connection result)
    {
        C connection = this.connection = (C)result;
        if (connect.compareAndSet(ConnectState.CONNECTING, ConnectState.CONNECTED))
        {
            process(connection, true);
        }
        else
        {
            connection.close();
            failed(new IllegalStateException());
        }
    }

    @Override
    public void failed(Throwable x)
    {
        connect.set(ConnectState.DISCONNECTED);
    }

    protected boolean process(final C connection, boolean dispatch)
    {
        HttpClient client = getHttpClient();
        final HttpExchange exchange = getHttpExchanges().poll();
        LOG.debug("Processing {} on {}", exchange, connection);
        if (exchange == null)
            return false;

        final Request request = exchange.getRequest();
        Throwable cause = request.getAbortCause();
        if (cause != null)
        {
            LOG.debug("Aborted before processing {}: {}", exchange, cause);
            // It may happen that the request is aborted before the exchange
            // is created. Aborting the exchange a second time will result in
            // a no-operation, so we just abort here to cover that edge case.
            exchange.abort(cause);
        }
        else
        {
            if (dispatch)
            {
                client.getExecutor().execute(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        send(connection, exchange);
                    }
                });
            }
            else
            {
                send(connection, exchange);
            }
        }
        return true;
    }

    @Override
    public void close()
    {
        super.close();
        C connection = this.connection;
        if (connection != null)
            connection.close();
    }

    @Override
    public void close(Connection connection)
    {
        super.close(connection);
        while (true)
        {
            ConnectState current = connect.get();
            if (connect.compareAndSet(current, ConnectState.DISCONNECTED))
            {
                if (getHttpClient().isRemoveIdleDestinations())
                    getHttpClient().removeDestination(this);
                break;
            }
        }
    }

    protected abstract void send(C connection, HttpExchange exchange);

    private enum ConnectState
    {
        DISCONNECTED, CONNECTING, CONNECTED
    }
}
