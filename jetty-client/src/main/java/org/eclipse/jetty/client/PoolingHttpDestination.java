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

import java.io.IOException;
import java.util.Arrays;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

public abstract class PoolingHttpDestination<C extends Connection> extends HttpDestination implements Promise<Connection>
{
    private final ConnectionPool connectionPool;

    public PoolingHttpDestination(HttpClient client, Origin origin)
    {
        super(client, origin);
        this.connectionPool = newConnectionPool(client);
    }

    protected ConnectionPool newConnectionPool(HttpClient client)
    {
        return new ConnectionPool(this, client.getMaxConnectionsPerDestination(), this);
    }

    public ConnectionPool getConnectionPool()
    {
        return connectionPool;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void succeeded(Connection connection)
    {
        process((C)connection, true);
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
        C connection = acquire();
        if (connection != null)
            process(connection, false);
    }

    @SuppressWarnings("unchecked")
    public C acquire()
    {
        return (C)connectionPool.acquire();
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
    public void process(final C connection, boolean dispatch)
    {
        HttpClient client = getHttpClient();
        final HttpExchange exchange = getHttpExchanges().poll();
        LOG.debug("Processing exchange {} on connection {}", exchange, connection);
        if (exchange == null)
        {
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
        }
    }

    protected abstract void send(C connection, HttpExchange exchange);

    @Override
    public void release(Connection c)
    {
        @SuppressWarnings("unchecked")
        C connection = (C)c;
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
            close(connection);
            connection.close();
        }
    }

    @Override
    public void close(Connection oldConnection)
    {
        super.close(oldConnection);
        connectionPool.remove(oldConnection);

        if (getHttpExchanges().isEmpty())
        {
            if (getHttpClient().isRemoveIdleDestinations() && connectionPool.isEmpty())
            {
                // There is a race condition between this thread removing the destination
                // and another thread queueing a request to this same destination.
                // If this destination is removed, but the request queued, a new connection
                // will be opened, the exchange will be executed and eventually the connection
                // will idle timeout and be closed. Meanwhile a new destination will be created
                // in HttpClient and will be used for other requests.
                getHttpClient().removeDestination(this);
            }
        }
        else
        {
            // We need to execute queued requests even if this connection failed.
            // We may create a connection that is not needed, but it will eventually
            // idle timeout, so no worries.
            C newConnection = acquire();
            if (newConnection != null)
                process(newConnection, false);
        }
    }

    public void close()
    {
        super.close();
        connectionPool.close();
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        ContainerLifeCycle.dump(out, indent, Arrays.asList(connectionPool));
    }
}
