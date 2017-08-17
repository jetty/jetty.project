//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.thread.Sweeper;

@ManagedObject
public abstract class PoolingHttpDestination<C extends Connection> extends HttpDestination implements Callback
{
    private DuplexConnectionPool connectionPool;

    public PoolingHttpDestination(HttpClient client, Origin origin)
    {
        super(client, origin);
    }

    @Override
    protected void doStart() throws Exception
    {
        HttpClient client = getHttpClient();
        this.connectionPool = newConnectionPool(client);
        addBean(connectionPool);
        super.doStart();
        Sweeper sweeper = client.getBean(Sweeper.class);
        if (sweeper != null)
            sweeper.offer(connectionPool);
    }

    @Override
    protected void doStop() throws Exception
    {
        HttpClient client = getHttpClient();
        Sweeper sweeper = client.getBean(Sweeper.class);
        if (sweeper != null)
            sweeper.remove(connectionPool);
        super.doStop();
        removeBean(connectionPool);
    }

    protected DuplexConnectionPool newConnectionPool(HttpClient client)
    {
        return new DuplexConnectionPool(this, client.getMaxConnectionsPerDestination(), this);
    }

    @ManagedAttribute(value = "The connection pool", readonly = true)
    public DuplexConnectionPool getConnectionPool()
    {
        return connectionPool;
    }

    @Override
    public void succeeded()
    {
        send();
    }

    @Override
    public void failed(final Throwable x)
    {
        abort(x);
    }

    public void send()
    {
        if (getHttpExchanges().isEmpty())
            return;
        process();
    }

    @SuppressWarnings("unchecked")
    public C acquire()
    {
        return (C)connectionPool.acquire();
    }

    private void process()
    {
        while (true)
        {
            C connection = acquire();
            if (connection == null)
                break;
            boolean proceed = process(connection);
            if (!proceed)
                break;
        }
    }

    /**
     * <p>Processes a new connection making it idle or active depending on whether requests are waiting to be sent.</p>
     * <p>A new connection is created when a request needs to be executed; it is possible that the request that
     * triggered the request creation is executed by another connection that was just released, so the new connection
     * may become idle.</p>
     * <p>If a request is waiting to be executed, it will be dequeued and executed by the new connection.</p>
     *
     * @param connection the new connection
     * @return whether to perform more processing
     */
    public boolean process(final C connection)
    {
        HttpClient client = getHttpClient();
        final HttpExchange exchange = getHttpExchanges().poll();
        if (LOG.isDebugEnabled())
            LOG.debug("Processing exchange {} on {} of {}", exchange, connection, this);
        if (exchange == null)
        {
            if (!connectionPool.release(connection))
                connection.close();
            if (!client.isRunning())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} is stopping", client);
                connection.close();
            }
            return false;
        }
        else
        {
            final Request request = exchange.getRequest();
            Throwable cause = request.getAbortCause();
            if (cause != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Aborted before processing {}: {}", exchange, cause);
                // Won't use this connection, release it back.
                if (!connectionPool.release(connection))
                    connection.close();
                // It may happen that the request is aborted before the exchange
                // is created. Aborting the exchange a second time will result in
                // a no-operation, so we just abort here to cover that edge case.
                exchange.abort(cause);
            }
            else
            {
                SendFailure result = send(connection, exchange);
                if (result != null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Send failed {} for {}", result, exchange);
                    if (result.retry)
                    {
                        if (enqueue(getHttpExchanges(), exchange))
                            return true;
                    }

                    request.abort(result.failure);
                }
            }
            return getHttpExchanges().peek() != null;
        }
    }

    protected abstract SendFailure send(C connection, HttpExchange exchange);

    @Override
    public void release(Connection c)
    {
        @SuppressWarnings("unchecked")
        C connection = (C)c;
        if (LOG.isDebugEnabled())
            LOG.debug("Released {}", connection);
        HttpClient client = getHttpClient();
        if (client.isRunning())
        {
            if (connectionPool.isActive(connection))
            {
                if (connectionPool.release(connection))
                    send();
                else
                    connection.close();
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Released explicit {}", connection);
            }
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} is stopped", client);
            connection.close();
        }
    }

    @Override
    public void close(Connection connection)
    {
        super.close(connection);

        boolean removed = connectionPool.remove(connection);

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
            if (removed)
                process();
        }
    }

    public void close()
    {
        super.close();
        connectionPool.close();
    }

    @Override
    public String toString()
    {
        return String.format("%s,pool=%s", super.toString(), connectionPool);
    }
}
