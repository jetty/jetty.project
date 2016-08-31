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

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.Sweeper;

@ManagedObject
public abstract class HttpDestination extends ContainerLifeCycle implements Destination, Closeable, Callback, Dumpable
{
    protected static final Logger LOG = Log.getLogger(HttpDestination.class);

    private final HttpClient client;
    private final Origin origin;
    private final Queue<HttpExchange> exchanges;
    private final RequestNotifier requestNotifier;
    private final ResponseNotifier responseNotifier;
    private final ProxyConfiguration.Proxy proxy;
    private final ClientConnectionFactory connectionFactory;
    private final HttpField hostField;
    private ConnectionPool connectionPool;

    public HttpDestination(HttpClient client, Origin origin)
    {
        this.client = client;
        this.origin = origin;

        this.exchanges = newExchangeQueue(client);

        this.requestNotifier = new RequestNotifier(client);
        this.responseNotifier = new ResponseNotifier();

        ProxyConfiguration proxyConfig = client.getProxyConfiguration();
        proxy = proxyConfig.match(origin);
        ClientConnectionFactory connectionFactory = client.getTransport();
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
        this.connectionFactory = connectionFactory;

        String host = HostPort.normalizeHost(getHost());
        if (!client.isDefaultPort(getScheme(), getPort()))
            host += ":" + getPort();
        hostField = new HttpField(HttpHeader.HOST, host);
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

    protected abstract ConnectionPool newConnectionPool(HttpClient client);

    protected Queue<HttpExchange> newExchangeQueue(HttpClient client)
    {
        return new BlockingArrayQueue<>(client.getMaxRequestsQueuedPerDestination());
    }

    protected ClientConnectionFactory newSslClientConnectionFactory(ClientConnectionFactory connectionFactory)
    {
        return new SslClientConnectionFactory(client.getSslContextFactory(), client.getByteBufferPool(), client.getExecutor(), connectionFactory);
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

        HttpExchange exchange = new HttpExchange(this, request, listeners);

        if (client.isRunning())
        {
            if (enqueue(exchanges, exchange))
            {
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
                request.abort(new RejectedExecutionException("Max requests per destination " + client.getMaxRequestsQueuedPerDestination() + " exceeded for " + this));
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

    protected abstract SendFailure send(Connection connection, HttpExchange exchange);

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

    public void close()
    {
        abort(new AsynchronousCloseException());
        if (LOG.isDebugEnabled())
            LOG.debug("Closed {}", this);
        connectionPool.close();
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
    }

    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        ContainerLifeCycle.dumpObject(out, toString());
        ContainerLifeCycle.dump(out, indent, Collections.singletonList(connectionPool));
    }

    public String asString()
    {
        return origin.asString();
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
}
