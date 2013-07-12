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

package org.eclipse.jetty.client.spdy;

import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.util.Promise;

public class HttpDestinationOverSPDY extends HttpDestination implements Promise<Connection>
{
    private final AtomicReference<ConnectState> connect = new AtomicReference<>(ConnectState.DISCONNECTED);
    private volatile HttpConnectionOverSPDY connection;

    public HttpDestinationOverSPDY(HttpClient client, String scheme, String host, int port)
    {
        super(client, scheme, host, port);
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
                        continue;
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
                    HttpConnectionOverSPDY connection = this.connection;
                    if (connection != null)
                        process(connection, false);
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
    }

    @Override
    public void succeeded(Connection result)
    {
        if (connect.compareAndSet(ConnectState.CONNECTING, ConnectState.CONNECTED))
        {
            HttpConnectionOverSPDY connection = this.connection = (HttpConnectionOverSPDY)result;
            process(connection, true);
        }
        else
        {
            result.close();
            failed(new IllegalStateException());
        }
    }

    @Override
    public void failed(Throwable x)
    {
        connect.set(ConnectState.DISCONNECTED);
    }

    private void process(final HttpConnectionOverSPDY connection, boolean dispatch)
    {
        HttpClient client = getHttpClient();
        final HttpExchange exchange = getHttpExchanges().poll();
        LOG.debug("Processing exchange {} on connection {}", exchange, connection);
        if (exchange != null)
        {
            final Request request = exchange.getRequest();
            Throwable cause = request.getAbortCause();
            if (cause != null)
            {
                abort(exchange, cause);
                LOG.debug("Aborted before processing {}: {}", exchange, cause);
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
                            connection.send(exchange);
                        }
                    });
                }
                else
                {
                    connection.send(exchange);
                }
            }
        }
    }

    private enum ConnectState
    {
        DISCONNECTED, CONNECTING, CONNECTED
    }
}
