//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client.transport;

import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.Connection;
import org.eclipse.jetty.client.ConnectionPool;
import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.ProxyConfiguration;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.RetryableRequestException;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.CyclicTimeouts;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.Sweeper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ManagedObject
public class HttpDestination extends ContainerLifeCycle implements Destination, Callback, Dumpable, Sweeper.Sweepable
{
    private static final Logger LOG = LoggerFactory.getLogger(HttpDestination.class);

    private final HttpClient client;
    private final Origin origin;
    private final Queue<HttpExchange> exchanges;
    private final ProxyConfiguration.Proxy proxy;
    private final ClientConnectionFactory connectionFactory;
    private final HttpField hostField;
    private final RequestTimeouts requestTimeouts;
    private final AutoLock staleLock = new AutoLock();
    private ConnectionPool connectionPool;
    private boolean stale;
    private long activeNanoTime;

    /**
     * <p>Creates a new HTTP destination.</p>
     *
     * @param client the {@link HttpClient}
     * @param origin the {@link Origin}
     */
    public HttpDestination(HttpClient client, Origin origin)
    {
        this.client = client;
        this.origin = origin;

        this.exchanges = newExchangeQueue(client);

        this.requestTimeouts = new RequestTimeouts(client.getScheduler());

        String host = HostPort.normalizeHost(getHost());
        int port = getPort();
        String scheme = getScheme();
        if (port != URIUtil.getDefaultPortForScheme(scheme))
            host += ":" + port;
        hostField = new HttpField(HttpHeader.HOST, host);

        ProxyConfiguration proxyConfig = client.getProxyConfiguration();
        proxy = proxyConfig.match(origin);

        ClientConnectionFactory connectionFactory = client.getHttpClientTransport();
        if (proxy != null)
            connectionFactory = proxy.newClientConnectionFactory(connectionFactory);
        this.connectionFactory = connectionFactory;
    }

    public void accept(Connection connection)
    {
        connectionPool.accept(connection);
    }

    public boolean stale()
    {
        try (AutoLock ignored = staleLock.lock())
        {
            boolean stale = this.stale;
            if (!stale)
                this.activeNanoTime = NanoTime.now();
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
        try (AutoLock ignored = staleLock.lock())
        {
            boolean stale = exchanges.isEmpty() && connectionPool.isEmpty();
            if (!stale)
            {
                this.activeNanoTime = NanoTime.now();
            }
            else if (NanoTime.millisSince(activeNanoTime) >= getHttpClient().getDestinationIdleTimeout())
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

    @Override
    protected void doStart() throws Exception
    {
        this.connectionPool = newConnectionPool(client);
        addBean(connectionPool, true);
        this.activeNanoTime = NanoTime.now();
        super.doStart();
        Sweeper connectionPoolSweeper = client.getBean(Sweeper.class);
        if (connectionPoolSweeper != null && connectionPool instanceof Sweeper.Sweepable)
            connectionPoolSweeper.offer((Sweeper.Sweepable)connectionPool);
    }

    @Override
    protected void doStop() throws Exception
    {
        requestTimeouts.destroy();
        abortExchanges(new AsynchronousCloseException());
        Sweeper connectionPoolSweeper = client.getBean(Sweeper.class);
        if (connectionPoolSweeper != null && connectionPool instanceof Sweeper.Sweepable)
            connectionPoolSweeper.remove((Sweeper.Sweepable)connectionPool);
        super.doStop();
        removeBean(connectionPool);
    }

    protected ConnectionPool newConnectionPool(HttpClient client)
    {
        return client.getHttpClientTransport().getConnectionPoolFactory().newConnectionPool(this);
    }

    protected Queue<HttpExchange> newExchangeQueue(HttpClient client)
    {
        int maxCapacity = client.getMaxRequestsQueuedPerDestination();
        if (maxCapacity > 32)
            return BlockingArrayQueue.newInstance(32, maxCapacity);
        return new BlockingArrayQueue<>(maxCapacity);
    }

    @Override
    @ManagedAttribute("Whether this destination is secure")
    public boolean isSecure()
    {
        return origin.isSecure();
    }

    @Override
    @ManagedAttribute("The HttpClient")
    public HttpClient getHttpClient()
    {
        return client;
    }

    @Override
    public Origin getOrigin()
    {
        return origin;
    }

    public Queue<HttpExchange> getHttpExchanges()
    {
        return exchanges;
    }

    @Override
    public ProxyConfiguration.Proxy getProxy()
    {
        return proxy;
    }

    public Origin resolveOrigin()
    {
        return proxy == null ? getOrigin() : proxy.getOrigin();
    }

    public ClientConnectionFactory resolveClientConnectionFactory()
    {
        return connectionFactory;
    }

    public SslContextFactory.Client resolveSslContextFactory()
    {
        boolean secure = proxy == null ? isSecure() : proxy.isSecure();
        if (secure)
        {
            SslContextFactory.Client sslContextFactory = getHttpClient().getSslContextFactory();
            if (proxy == null)
                return sslContextFactory;
            SslContextFactory.Client proxySslContextFactory = proxy.getSslContextFactory();
            return proxySslContextFactory != null ? proxySslContextFactory : sslContextFactory;
        }
        return null;
    }

    @ManagedAttribute("The destination scheme")
    public String getScheme()
    {
        return getOrigin().getScheme();
    }

    @ManagedAttribute("The destination host")
    public String getHost()
    {
        // InetSocketAddress.getHostString() transforms the host string
        // in case of IPv6 addresses, so we return the original host string
        return getOrigin().getAddress().getHost();
    }

    @ManagedAttribute("The destination port")
    public int getPort()
    {
        return getOrigin().getAddress().getPort();
    }

    @ManagedAttribute("The number of queued requests")
    public int getQueuedRequestCount()
    {
        return exchanges.size();
    }

    public HttpField getHostField()
    {
        return hostField;
    }

    @ManagedAttribute("The connection pool")
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
        abortExchanges(x);
    }

    @Override
    public void send(Request request, Response.CompleteListener listener)
    {
        ((HttpRequest)request).sendAsync(this, listener);
    }

    void send(HttpRequest request)
    {
        send(new HttpExchange(this, request));
    }

    public void send(HttpExchange exchange)
    {
        HttpRequest request = exchange.getRequest();
        if (client.isRunning())
        {
            if (enqueue(exchanges, exchange))
            {
                request.sent();
                requestTimeouts.schedule(exchange);
                if (!client.isRunning() && exchanges.remove(exchange))
                {
                    request.abort(new RejectedExecutionException(client + " is stopping"));
                }
                else
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Queued {} for {}", request, this);
                    if (!request.getAndSetQueued())
                        request.notifyQueued();
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
            Connection connection = connectionPool.acquire(create);
            if (connection == null)
            {
                if (!isRunning())
                {
                    // This instance is being used after becoming stale: the sweeper stops the destination in such case
                    // which itself stops the connection pool; the latter only returns null for two reasons: the pool
                    // being empty or stopped, so we differentiate between the two by checking the running state.
                    while (true)
                    {
                        HttpExchange httpExchange = exchanges.poll();
                        if (httpExchange == null)
                            break;
                        httpExchange.abort(new RejectedExecutionException(this + " is stale"), Promise.noop());
                    }
                }
                break;
            }
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
            releaseOrClose(connection);
            if (!client.isRunning())
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} is stopping", client);
                connection.close();
            }
            return false;
        }

        Request request = exchange.getRequest();
        Throwable cause = request.getAbortCause();
        if (cause != null)
        {
            if (LOG.isDebugEnabled())
                LOG.atDebug().setCause(cause).log("Aborted before processing {}", exchange);
            // It may happen that the request is aborted before the exchange
            // is created. Aborting the exchange a second time will result in
            // a no-operation, so we just abort here to cover that edge case.
            exchange.abort(cause, Promise.from(() ->
            {
                // The exchange won't be associated to a connection,
                // and this connection won't be used, release it back.
                releaseOrClose(connection);
            }));
            return getQueuedRequestCount() > 0;
        }

        SendFailure failure = send((IConnection)connection, exchange);
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
            try
            {
                // Resend this exchange, on another connection or another
                // destination, and return false to avoid re-entering this method.
                HttpDestination newDestination = (HttpDestination)client.resolveDestination(request);
                newDestination.send(exchange);
                releaseOrClose(connection);
                return false;
            }
            catch (Throwable x)
            {
                ExceptionUtil.addSuppressedIfNotAssociated(x, failure.failure);
                failure = new SendFailure(x, false);
                // Fall through.
            }
        }

        request.abort(failure.failure);
        releaseOrClose(connection);
        return getQueuedRequestCount() > 0;
    }

    protected SendFailure send(IConnection connection, HttpExchange exchange)
    {
        try
        {
            return connection.send(exchange);
        }
        catch (Throwable x)
        {
            return new SendFailure(x, x instanceof RetryableRequestException);
        }
    }

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

    public void release(Connection connection)
    {
        HttpClient client = getHttpClient();
        if (client.isRunning())
        {
            if (connectionPool.isActive(connection))
            {
                // Trigger the next request after releasing the connection.
                boolean released = releaseOrClose(connection);
                if (LOG.isDebugEnabled())
                    LOG.debug("Released: {} {}", released, connection);
                send(!released);
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
                LOG.debug("Closing {}, {} is stopped", connection, client);
            connection.close();
        }
    }

    private boolean releaseOrClose(Connection connection)
    {
        boolean released = connectionPool.release(connection);
        if (!released)
            connection.close();
        return released;
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
     * Aborts all the {@link HttpExchange}s queued in this destination.
     *
     * @param cause the abort cause
     */
    private void abortExchanges(Throwable cause)
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
        return getOrigin().asString();
    }

    @ManagedAttribute("For how long this destination has been idle in ms")
    public long getIdle()
    {
        if (getHttpClient().getDestinationIdleTimeout() <= 0L)
            return -1;
        try (AutoLock ignored = staleLock.lock())
        {
            return NanoTime.millisSince(activeNanoTime);
        }
    }

    @ManagedAttribute("Whether this destinations is stale")
    public boolean isStale()
    {
        try (AutoLock ignored = staleLock.lock())
        {
            return this.stale;
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]@%x%s,state=%s,queue=%d,pool=%s,stale=%b,idle=%d",
            HttpDestination.class.getSimpleName(),
            getOrigin(),
            hashCode(),
            proxy == null ? "" : "(via " + proxy + ")",
            getState(),
            getQueuedRequestCount(),
            getConnectionPool(),
            isStale(),
            getIdle());
    }

    /**
     * <p>Enforces the total timeout for exchanges that are still in the queue.</p>
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
            // The implementation of the Iterator returned above does not support
            // removal, but the HttpExchange will be removed by request.abort().
            return false;
        }
    }
}
