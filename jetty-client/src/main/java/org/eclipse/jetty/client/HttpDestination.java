//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Locker;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.Sweeper;

@ManagedObject
public abstract class HttpDestination extends ContainerLifeCycle implements Destination, Closeable, Callback, Dumpable, Sweeper.Sweepable
{
    private static final Logger LOG = Log.getLogger(HttpDestination.class);

    private final HttpClient client;
    private final Origin origin;
    private final Queue<HttpExchange> exchanges;
    private final RequestNotifier requestNotifier;
    private final ResponseNotifier responseNotifier;
    private final ProxyConfiguration.Proxy proxy;
    private final ClientConnectionFactory connectionFactory;
    private final HttpField hostField;
    private final RequestTimeouts requestTimeouts;
    private final Locker staleLock = new Locker();
    private ConnectionPool connectionPool;
    private boolean stale;
    private long activeNanos;
    private Sweeper idleDestinationsSweeper;

    public HttpDestination(HttpClient client, Origin origin)
    {
        this.client = client;
        this.origin = origin;

        this.exchanges = newExchangeQueue(client);

        this.requestNotifier = new RequestNotifier(client);
        this.responseNotifier = new ResponseNotifier();

        this.requestTimeouts = new RequestTimeouts(client.getScheduler());

        ProxyConfiguration proxyConfig = client.getProxyConfiguration();
        proxy = proxyConfig.match(origin);
        ClientConnectionFactory connectionFactory = client.getTransport();
        if (proxy != null)
        {
            connectionFactory = proxy.newClientConnectionFactory(connectionFactory);
            if (proxy.isSecure())
                connectionFactory = newSslClientConnectionFactory(proxy.getSslContextFactory(), connectionFactory);
        }
        else
        {
            if (isSecure())
                connectionFactory = newSslClientConnectionFactory(null, connectionFactory);
        }
        Object tag = origin.getTag();
        if (tag instanceof ClientConnectionFactory.Decorator)
            connectionFactory = ((ClientConnectionFactory.Decorator)tag).apply(connectionFactory);
        this.connectionFactory = connectionFactory;

        String host = HostPort.normalizeHost(getHost());
        if (!client.isDefaultPort(getScheme(), getPort()))
            host += ":" + getPort();
        hostField = new HttpField(HttpHeader.HOST, host);
    }

    public boolean stale()
    {
        try (Locker.Lock l = staleLock.lock())
        {
            boolean stale = this.stale;
            if (!stale)
                this.activeNanos = System.nanoTime();
            if (LOG.isDebugEnabled())
                LOG.debug("Stale check done with result {} on {}", stale, this);
            return stale;
        }
    }

    @Override
    public boolean sweep()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Sweep check in progress on {}", this);
        boolean remove = false;
        try (Locker.Lock l = staleLock.lock())
        {
            boolean stale = exchanges.isEmpty() && connectionPool.isEmpty();
            if (!stale)
            {
                this.activeNanos = System.nanoTime();
            }
            else if (isStaleDelayExpired())
            {
                this.stale = true;
                remove = true;
            }
        }
        if (remove)
        {
            getHttpClient().removeDestination(this);
            LifeCycle.stop(this);
        }
        if (LOG.isDebugEnabled())
            LOG.debug("Sweep check done with result {} on {}", remove, this);
        return remove;
    }

    private boolean isStaleDelayExpired()
    {
        assert staleLock.isLocked();
        long destinationIdleTimeout = TimeUnit.MILLISECONDS.toNanos(getHttpClient().getDestinationIdleTimeout());
        return System.nanoTime() - activeNanos >= destinationIdleTimeout;
    }

    @Override
    protected void doStart() throws Exception
    {
        this.connectionPool = newConnectionPool(client);
        addBean(connectionPool, true);
        super.doStart();
        if (getHttpClient().getDestinationIdleTimeout() > 0)
        {
            idleDestinationsSweeper = new Sweeper(getHttpClient().getScheduler(), 1000L);
            addBean(idleDestinationsSweeper);
            idleDestinationsSweeper.offer(this);
        }
        Sweeper connectionPoolSweeper = client.getBean(Sweeper.class);
        if (connectionPoolSweeper != null && connectionPool instanceof Sweeper.Sweepable)
            connectionPoolSweeper.offer((Sweeper.Sweepable)connectionPool);
    }

    @Override
    protected void doStop() throws Exception
    {
        Sweeper connectionPoolSweeper = client.getBean(Sweeper.class);
        if (connectionPoolSweeper != null && connectionPool instanceof Sweeper.Sweepable)
            connectionPoolSweeper.remove((Sweeper.Sweepable)connectionPool);
        if (idleDestinationsSweeper != null)
        {
            idleDestinationsSweeper.remove(this);
            removeBean(idleDestinationsSweeper);
        }
        super.doStop();
        removeBean(connectionPool);
    }

    protected ConnectionPool newConnectionPool(HttpClient client)
    {
        return client.getTransport().getConnectionPoolFactory().newConnectionPool(this);
    }

    protected Queue<HttpExchange> newExchangeQueue(HttpClient client)
    {
        return new BlockingArrayQueue<>(client.getMaxRequestsQueuedPerDestination());
    }

    /**
     * Creates a new {@code SslClientConnectionFactory} wrapping the given connection factory.
     *
     * @param connectionFactory the connection factory to wrap
     * @return a new SslClientConnectionFactory
     * @deprecated use {@link #newSslClientConnectionFactory(SslContextFactory, ClientConnectionFactory)} instead
     */
    @Deprecated
    protected ClientConnectionFactory newSslClientConnectionFactory(ClientConnectionFactory connectionFactory)
    {
        return client.newSslClientConnectionFactory(null, connectionFactory);
    }

    protected ClientConnectionFactory newSslClientConnectionFactory(SslContextFactory sslContextFactory, ClientConnectionFactory connectionFactory)
    {
        if (sslContextFactory == null)
            return newSslClientConnectionFactory(connectionFactory);
        return client.newSslClientConnectionFactory(sslContextFactory, connectionFactory);
    }

    public boolean isSecure()
    {
        return HttpClient.isSchemeSecure(getScheme());
    }

    public HttpClient getHttpClient()
    {
        return client;
    }

    public Origin getOrigin()
    {
        return origin;
    }

    public Queue<HttpExchange> getHttpExchanges()
    {
        return exchanges;
    }

    public RequestNotifier getRequestNotifier()
    {
        return requestNotifier;
    }

    public ResponseNotifier getResponseNotifier()
    {
        return responseNotifier;
    }

    public ProxyConfiguration.Proxy getProxy()
    {
        return proxy;
    }

    public ClientConnectionFactory getClientConnectionFactory()
    {
        return connectionFactory;
    }

    @Override
    @ManagedAttribute(value = "The destination scheme", readonly = true)
    public String getScheme()
    {
        return origin.getScheme();
    }

    @Override
    @ManagedAttribute(value = "The destination host", readonly = true)
    public String getHost()
    {
        // InetSocketAddress.getHostString() transforms the host string
        // in case of IPv6 addresses, so we return the original host string
        return origin.getAddress().getHost();
    }

    @Override
    @ManagedAttribute(value = "The destination port", readonly = true)
    public int getPort()
    {
        return origin.getAddress().getPort();
    }

    @ManagedAttribute(value = "The number of queued requests", readonly = true)
    public int getQueuedRequestCount()
    {
        return exchanges.size();
    }

    public Origin.Address getConnectAddress()
    {
        return proxy == null ? origin.getAddress() : proxy.getAddress();
    }

    public HttpField getHostField()
    {
        return hostField;
    }

    @ManagedAttribute(value = "The connection pool", readonly = true)
    public ConnectionPool getConnectionPool()
    {
        return connectionPool;
    }

    @Override
    public void succeeded()
    {
        send(false);
    }

    @Override
    public void failed(Throwable x)
    {
        abort(x);
    }

    public void send(Request request, Response.CompleteListener listener)
    {
        ((HttpRequest)request).sendAsync(this, listener);
    }

    protected void send(HttpRequest request, List<Response.ResponseListener> listeners)
    {
        send(new HttpExchange(this, request, listeners));
    }

    public void send(HttpExchange exchange)
    {
        HttpRequest request = exchange.getRequest();
        if (client.isRunning())
        {
            if (enqueue(exchanges, exchange))
            {
                requestTimeouts.schedule(exchange);
                if (!client.isRunning() && exchanges.remove(exchange))
                {
                    request.abort(new RejectedExecutionException(client + " is stopping"));
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Queued {} for {}", request, this);
                    requestNotifier.notifyQueued(request);
                    send();
                }
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Max queue size {} exceeded by {} for {}", client.getMaxRequestsQueuedPerDestination(), request, this);
                request.abort(new RejectedExecutionException("Max requests queued per destination " + client.getMaxRequestsQueuedPerDestination() + " exceeded for " + this));
            }
        }
        else
        {
            request.abort(new RejectedExecutionException(client + " is stopped"));
        }
    }

    protected boolean enqueue(Queue<HttpExchange> queue, HttpExchange exchange)
    {
        return queue.offer(exchange);
    }

    public void send()
    {
        send(true);
    }

    private void send(boolean create)
    {
        if (!getHttpExchanges().isEmpty())
            process(create);
    }

    private void process(boolean create)
    {
        // The loop is necessary in case of a new multiplexed connection,
        // when a single thread notified of the connection opening must
        // process all queued exchanges.
        // It is also necessary when thread T1 cannot acquire a connection
        // (for example, it has been stolen by thread T2 and the pool has
        // enough pending reservations). T1 returns without doing anything
        // and therefore it is T2 that must send both queued requests.
        while (true)
        {
            Connection connection;
            if (connectionPool instanceof AbstractConnectionPool)
                connection = ((AbstractConnectionPool)connectionPool).acquire(create);
            else
                connection = connectionPool.acquire();
            if (connection == null)
                break;
            boolean proceed = process(connection);
            if (proceed)
                create = false;
            else
                break;
        }
    }

    private boolean process(Connection connection)
    {
        HttpClient client = getHttpClient();
        HttpExchange exchange = getHttpExchanges().poll();
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
            Request request = exchange.getRequest();
            Throwable cause = request.getAbortCause();
            if (cause != null)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Aborted before processing {}: {}", exchange, cause);
                // Won't use this connection, release it back.
                boolean released = connectionPool.release(connection);
                if (!released)
                    connection.close();
                // It may happen that the request is aborted before the exchange
                // is created. Aborting the exchange a second time will result in
                // a no-operation, so we just abort here to cover that edge case.
                exchange.abort(cause);
                return getQueuedRequestCount() > 0;
            }

            SendFailure failure = send(connection, exchange);
            if (failure == null)
            {
                // Aggressively send other queued requests
                // in case connections are multiplexed.
                return getQueuedRequestCount() > 0;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Send failed {} for {}", failure, exchange);
            if (failure.retry)
            {
                // Resend this exchange, likely on another connection,
                // and return false to avoid to re-enter this method.
                send(exchange);
                return false;
            }
            request.abort(failure.failure);
            return getQueuedRequestCount() > 0;
        }
    }

    protected abstract SendFailure send(Connection connection, HttpExchange exchange);

    @Override
    public void newConnection(Promise<Connection> promise)
    {
        createConnection(promise);
    }

    protected void createConnection(Promise<Connection> promise)
    {
        client.newConnection(this, promise);
    }

    public boolean remove(HttpExchange exchange)
    {
        return exchanges.remove(exchange);
    }

    @Override
    public void close()
    {
        abort(new AsynchronousCloseException());
        if (LOG.isDebugEnabled())
            LOG.debug("Closed {}", this);
        connectionPool.close();
        requestTimeouts.destroy();
    }

    public void release(Connection connection)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Released {}", connection);
        HttpClient client = getHttpClient();
        if (client.isRunning())
        {
            if (connectionPool.isActive(connection))
            {
                // Trigger the next request after releasing the connection.
                if (connectionPool.release(connection))
                {
                    send(false);
                }
                else
                {
                    connection.close();
                    send(true);
                }
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

    public boolean remove(Connection connection)
    {
        boolean removed = connectionPool.remove(connection);

        if (removed)
        {
            // Process queued requests that may be waiting.
            // We may create a connection that is not
            // needed, but it will eventually idle timeout.
            send(true);
        }
        return removed;
    }

    /**
     * @param connection the connection to remove
     * @deprecated use {@link #remove(Connection)} instead
     */
    @Deprecated
    public void close(Connection connection)
    {
        remove(connection);
    }

    /**
     * Aborts all the {@link HttpExchange}s queued in this destination.
     *
     * @param cause the abort cause
     */
    public void abort(Throwable cause)
    {
        // Copy the queue of exchanges and fail only those that are queued at this moment.
        // The application may queue another request from the failure/complete listener
        // and we don't want to fail it immediately as if it was queued before the failure.
        // The call to Request.abort() will remove the exchange from the exchanges queue.
        for (HttpExchange exchange : new ArrayList<>(exchanges))
        {
            exchange.getRequest().abort(cause);
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent, new DumpableCollection("exchanges", exchanges));
    }

    public String asString()
    {
        return origin.asString();
    }

    @ManagedAttribute("For how long this destination has been idle in ms")
    public long getIdle()
    {
        if (idleDestinationsSweeper == null)
            return -1;
        try (Locker.Lock l = staleLock.lock())
        {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - activeNanos);
        }
    }

    @ManagedAttribute("Whether this destinations is stale")
    public boolean isStale()
    {
        try (Locker.Lock l = staleLock.lock())
        {
            return this.stale;
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]@%x%s,state=%s,queue=%d,pool=%s,stale=%s,idle=%d",
            HttpDestination.class.getSimpleName(),
            asString(),
            hashCode(),
            proxy == null ? "" : "(via " + proxy + ")",
            getState(),
            getQueuedRequestCount(),
            getConnectionPool(),
            isStale(),
            getIdle());
    }

    /**
     * <p>Enforces the total timeout for for exchanges that are still in the queue.</p>
     * <p>The total timeout for exchanges that are not in the destination queue
     * is enforced in {@link HttpConnection}.</p>
     */
    private class RequestTimeouts extends CyclicTimeouts<HttpExchange>
    {
        private RequestTimeouts(Scheduler scheduler)
        {
            super(scheduler);
        }

        @Override
        protected Iterator<HttpExchange> iterator()
        {
            return exchanges.iterator();
        }

        @Override
        protected boolean onExpired(HttpExchange exchange)
        {
            HttpRequest request = exchange.getRequest();
            request.abort(new TimeoutException("Total timeout " + request.getConversation().getTimeout() + " ms elapsed"));
            return false;
        }
    }
}
