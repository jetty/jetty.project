//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.CyclicTimeout;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.Sweeper;

@ManagedObject
public class HttpDestination extends ContainerLifeCycle implements Destination, Closeable, Callback, Dumpable
{
    protected static final Logger LOG = Log.getLogger(HttpDestination.class);

    private final HttpClient client;
    private final Info info;
    private final Queue<HttpExchange> exchanges;
    private final RequestNotifier requestNotifier;
    private final ResponseNotifier responseNotifier;
    private final ProxyConfiguration.Proxy proxy;
    private final ClientConnectionFactory connectionFactory;
    private final HttpField hostField;
    private final TimeoutTask timeout;
    private ConnectionPool connectionPool;

    @Deprecated
    public HttpDestination(HttpClient client, Origin origin)
    {
        this(client, new Info(origin, null));
    }

    public HttpDestination(HttpClient client, Info info)
    {
        this(client, info, Function.identity());
    }

    public HttpDestination(HttpClient client, Info info, Function<ClientConnectionFactory, ClientConnectionFactory> factoryFn)
    {
        this.client = client;
        this.info = info;

        this.exchanges = newExchangeQueue(client);

        this.requestNotifier = new RequestNotifier(client);
        this.responseNotifier = new ResponseNotifier();
        
        this.timeout = new TimeoutTask(client.getScheduler());

        String host = HostPort.normalizeHost(getHost());
        if (!client.isDefaultPort(getScheme(), getPort()))
            host += ":" + getPort();
        hostField = new HttpField(HttpHeader.HOST, host);

        ProxyConfiguration proxyConfig = client.getProxyConfiguration();
        this.proxy = proxyConfig.match(getOrigin());

        this.connectionFactory = factoryFn.apply(createClientConnectionFactory());
    }

    private ClientConnectionFactory createClientConnectionFactory()
    {
        ProxyConfiguration.Proxy proxy = getProxy();
        ClientConnectionFactory connectionFactory = getHttpClient().getTransport();
        if (proxy != null)
        {
            connectionFactory = proxy.newClientConnectionFactory(connectionFactory);
            if (proxy.isSecure())
                connectionFactory = newSslClientConnectionFactory(connectionFactory);
        }
        else
        {
            if (isSecure())
                connectionFactory = newSslClientConnectionFactory(connectionFactory);
        }
        return connectionFactory;
    }

    @Override
    protected void doStart() throws Exception
    {
        this.connectionPool = newConnectionPool(client);
        addBean(connectionPool);
        super.doStart();
        Sweeper sweeper = client.getBean(Sweeper.class);
        if (sweeper != null && connectionPool instanceof Sweeper.Sweepable)
            sweeper.offer((Sweeper.Sweepable)connectionPool);
    }

    @Override
    protected void doStop() throws Exception
    {
        Sweeper sweeper = client.getBean(Sweeper.class);
        if (sweeper != null && connectionPool instanceof Sweeper.Sweepable)
            sweeper.remove((Sweeper.Sweepable)connectionPool);
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

    protected ClientConnectionFactory newSslClientConnectionFactory(ClientConnectionFactory connectionFactory)
    {
        return client.newSslClientConnectionFactory(connectionFactory);
    }

    public boolean isSecure()
    {
        return HttpClient.isSchemeSecure(getScheme());
    }

    public HttpClient getHttpClient()
    {
        return client;
    }

    public Info getInfo()
    {
        return info;
    }

    public Origin getOrigin()
    {
        return info.origin;
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
        return getOrigin().getScheme();
    }

    @Override
    @ManagedAttribute(value = "The destination host", readonly = true)
    public String getHost()
    {
        // InetSocketAddress.getHostString() transforms the host string
        // in case of IPv6 addresses, so we return the original host string
        return getOrigin().getAddress().getHost();
    }

    @Override
    @ManagedAttribute(value = "The destination port", readonly = true)
    public int getPort()
    {
        return getOrigin().getAddress().getPort();
    }

    @ManagedAttribute(value = "The number of queued requests", readonly = true)
    public int getQueuedRequestCount()
    {
        return exchanges.size();
    }

    public Origin.Address getConnectAddress()
    {
        return proxy == null ? getOrigin().getAddress() : proxy.getAddress();
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
        send();
    }

    @Override
    public void failed(Throwable x)
    {
        abort(x);
    }

    protected void send(HttpRequest request, List<Response.ResponseListener> listeners)
    {        
        if (!getScheme().equalsIgnoreCase(request.getScheme()))
            throw new IllegalArgumentException("Invalid request scheme " + request.getScheme() + " for destination " + this);
        if (!getHost().equalsIgnoreCase(request.getHost()))
            throw new IllegalArgumentException("Invalid request host " + request.getHost() + " for destination " + this);
        int port = request.getPort();
        if (port >= 0 && getPort() != port)
            throw new IllegalArgumentException("Invalid request port " + port + " for destination " + this);
        send(new HttpExchange(this, request, listeners));
    }

    public void send(HttpExchange exchange)
    {
        HttpRequest request = exchange.getRequest();
        if (client.isRunning())
        {
            if (enqueue(exchanges, exchange))
            {
                long expiresAt = request.getTimeoutAt();
                if (expiresAt != -1)
                    timeout.schedule(expiresAt);

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
        if (getHttpExchanges().isEmpty())
            return;
        process();
    }

    private void process()
    {
        while (true)
        {
            Connection connection = connectionPool.acquire();
            if (connection == null)
                break;
            boolean proceed = process(connection);
            if (!proceed)
                break;
        }
    }

    public boolean process(final Connection connection)
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
                SendFailure result = ((IConnection)connection).send(exchange);
                if (result != null)
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("Send failed {} for {}", result, exchange);
                    if (result.retry)
                        send(exchange);
                    else
                        request.abort(result.failure);
                }
            }
            return getHttpExchanges().peek() != null;
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

    @Override
    public void close()
    {
        abort(new AsynchronousCloseException());
        if (LOG.isDebugEnabled())
            LOG.debug("Closed {}", this);
        connectionPool.close();
        timeout.destroy();
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

    public boolean remove(Connection connection)
    {
        return connectionPool.remove(connection);
    }

    public void close(Connection connection)
    {
        boolean removed = remove(connection);

        if (getHttpExchanges().isEmpty())
        {
            tryRemoveIdleDestination();
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
            exchange.getRequest().abort(cause);
        if (exchanges.isEmpty())
            tryRemoveIdleDestination();
    }

    private void tryRemoveIdleDestination()
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

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        dumpObjects(out, indent, new DumpableCollection("exchanges", exchanges));
    }

    public String asString()
    {
        return getInfo().asString();
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]@%x%s,queue=%d,pool=%s",
                HttpDestination.class.getSimpleName(),
                asString(),
                hashCode(),
                proxy == null ? "" : "(via " + proxy + ")",
                exchanges.size(),
                connectionPool);
    }

    @FunctionalInterface
    public interface Multiplexed
    {
        void setMaxRequestsPerConnection(int maxRequestsPerConnection);
    }

    public static class Info
    {
        private final Origin origin;
        private final Protocol protocol;

        public Info(Origin origin, Protocol protocol)
        {
            this.origin = origin;
            this.protocol = protocol;
        }

        public Origin getOrigin()
        {
            return origin;
        }

        public Protocol getProtocol()
        {
            return protocol;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            Info that = (Info)obj;
            return origin.equals(that.origin) && Objects.equals(protocol, that.protocol);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(origin, protocol);
        }

        public String asString()
        {
            return String.format("%s|%s", origin.asString(), protocol == null ? "null" : protocol.asString());
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[%s]", getClass().getSimpleName(), hashCode(), asString());
        }
    }

    public static class Protocol
    {
        private final List<String> protocols;
        private final boolean negotiate;
        private final String kind;

        public Protocol(List<String> protocols, boolean negotiate)
        {
            this(protocols, negotiate, null);
        }

        public Protocol(List<String> protocols, boolean negotiate, String kind)
        {
            this.protocols = protocols;
            this.negotiate = negotiate;
            this.kind = kind;
        }

        public List<String> getProtocols()
        {
            return protocols;
        }

        public boolean isNegotiate()
        {
            return negotiate;
        }

        public String getKind()
        {
            return kind;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            Protocol that = (Protocol)obj;
            return protocols.equals(that.protocols) &&
                    negotiate == that.negotiate &&
                    Objects.equals(kind, that.kind);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(protocols, negotiate, kind);
        }

        public String asString()
        {
            return String.format("proto=%s,nego=%b,kind=%s", protocols, negotiate, kind);
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[%s]", getClass().getSimpleName(), hashCode(), asString());
        }
    }

    // The TimeoutTask that expires when the next check of expiry is needed
    private class TimeoutTask extends CyclicTimeout
    {
        private final AtomicLong nextTimeout = new AtomicLong(Long.MAX_VALUE);

        private TimeoutTask(Scheduler scheduler)
        {
            super(scheduler);
        }

        @Override
        public void onTimeoutExpired()
        {
            nextTimeout.set(Long.MAX_VALUE);
            long now = System.nanoTime();
            long nextExpiresAt = Long.MAX_VALUE;
            
            // Check all queued exchanges for those that have expired
            // and to determine when the next check must be.
            for (HttpExchange exchange : exchanges)
            {
                HttpRequest request = exchange.getRequest();
                long expiresAt = request.getTimeoutAt();
                if (expiresAt == -1)
                    continue;
                if (expiresAt <= now)
                    request.abort(new TimeoutException("Total timeout " + request.getTimeout() + " ms elapsed"));
                else if (expiresAt < nextExpiresAt)
                    nextExpiresAt = expiresAt;
            }
            
            if (nextExpiresAt < Long.MAX_VALUE && client.isRunning())
                schedule(nextExpiresAt);
        }

        private void schedule(long expiresAt)
        {
            // Schedule a timeout for the soonest any known exchange can expire.
            // If subsequently that exchange is removed from the queue, the
            // timeout is not cancelled, instead the entire queue is swept
            // for expired exchanges and a new timeout is set.
            long timeoutAt = nextTimeout.getAndUpdate(e -> Math.min(e, expiresAt));
            if (timeoutAt != expiresAt)
            {
                long delay = expiresAt - System.nanoTime();
                if (LOG.isDebugEnabled())
                    LOG.debug("Scheduled timeout in {} ms", TimeUnit.NANOSECONDS.toMillis(delay));
                if (delay <= 0)
                    onTimeoutExpired();
                else
                    schedule(delay, TimeUnit.NANOSECONDS);
            }
        }
    }
}
