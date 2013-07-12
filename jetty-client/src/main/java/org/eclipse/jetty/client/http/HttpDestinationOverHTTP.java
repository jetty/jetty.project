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

package org.eclipse.jetty.client.http;

import java.io.IOException;
import java.util.Arrays;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpDestination;
import org.eclipse.jetty.client.HttpExchange;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

public class HttpDestinationOverHTTP extends HttpDestination  implements Promise<Connection>
{
    private final HttpConnectionPool connectionPool;

    public HttpDestinationOverHTTP(HttpClient client, String scheme, String host, int port)
    {
        super(client, scheme, host, port);
        this.connectionPool = new HttpConnectionPool(this, client.getMaxConnectionsPerDestination(), this);
    }

    public HttpConnectionPool getHttpConnectionPool()
    {
        return connectionPool;
    }

    @Override
    public void succeeded(Connection connection)
    {
        process((HttpConnectionOverHTTP)connection, true);
    }

    @Override
    public void failed(final Throwable x)
    {
        getHttpClient().getExecutor().execute(new Runnable()
        {
            @Override
            public void run()
            {
                abort(x);
            }
        });
    }

    protected void send()
    {
        HttpConnectionOverHTTP connection = acquire();
        if (connection != null)
            process(connection, false);
    }

    // TODO: make it protected
    public HttpConnectionOverHTTP acquire()
    {
        return (HttpConnectionOverHTTP)connectionPool.acquire();
    }

    /**
     * <p>Processes a new connection making it idle or active depending on whether requests are waiting to be sent.</p>
     * <p>A new connection is created when a request needs to be executed; it is possible that the request that
     * triggered the request creation is executed by another connection that was just released, so the new connection
     * may become idle.</p>
     * <p>If a request is waiting to be executed, it will be dequeued and executed by the new connection.</p>
     *
     * @param connection the new connection
     * @param dispatch whether to dispatch the processing to another thread
     */
    private void process(final HttpConnectionOverHTTP connection, boolean dispatch)
    {
        HttpClient client = getHttpClient();
        final HttpExchange exchange = getHttpExchanges().poll();
        LOG.debug("Processing exchange {} on connection {}", exchange, connection);
        if (exchange == null)
        {
            // TODO: review this part... may not be 100% correct
            // TODO: e.g. is client is not running, there should be no need to close the connection

            if (!connectionPool.release(connection))
                connection.close();

            if (!client.isRunning())
            {
                LOG.debug("{} is stopping", client);
                connection.close();
            }
        }
        else
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

    protected void release(HttpConnectionOverHTTP connection)
    {
        LOG.debug("{} released", connection);
        HttpClient client = getHttpClient();
        if (client.isRunning())
        {
            if (connectionPool.isActive(connection))
                process(connection, false);
            else
                LOG.debug("{} explicit", connection);
        }
        else
        {
            LOG.debug("{} is stopped", client);
            remove(connection);
            connection.close();
        }
    }

    protected void remove(HttpConnectionOverHTTP connection)
    {
        connectionPool.remove(connection);

        // We need to execute queued requests even if this connection failed.
        // We may create a connection that is not needed, but it will eventually
        // idle timeout, so no worries
        if (!getHttpExchanges().isEmpty())
        {
            connection = acquire();
            if (connection != null)
                process(connection, false);
        }
    }

    public void close()
    {
        connectionPool.close();
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        ContainerLifeCycle.dump(out, indent, Arrays.asList(connectionPool));
    }
}
