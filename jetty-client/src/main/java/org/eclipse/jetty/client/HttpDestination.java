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

import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.ProxyConfiguration;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class HttpDestination implements Destination, AutoCloseable, Dumpable
{
    private static final Logger LOG = Log.getLogger(HttpDestination.class);

    private final AtomicInteger connectionCount = new AtomicInteger();
    private final HttpClient client;
    private final String scheme;
    private final String host;
    private final Address address;
    private final Queue<HttpExchange> exchanges;
    private final BlockingQueue<Connection> idleConnections;
    private final BlockingQueue<Connection> activeConnections;
    private final RequestNotifier requestNotifier;
    private final ResponseNotifier responseNotifier;
    private final Address proxyAddress;
    private final HttpField hostField;

    public HttpDestination(HttpClient client, String scheme, String host, int port)
    {
        this.client = client;
        this.scheme = scheme;
        this.host = host;
        this.address = new Address(host, port);

        int maxRequestsQueued = client.getMaxRequestsQueuedPerDestination();
        int capacity = Math.min(32, maxRequestsQueued);
        this.exchanges = new BlockingArrayQueue<>(capacity, capacity, maxRequestsQueued);

        int maxConnections = client.getMaxConnectionsPerDestination();
        capacity = Math.min(8, maxConnections);
        this.idleConnections = new BlockingArrayQueue<>(capacity, capacity, maxConnections);
        this.activeConnections = new BlockingArrayQueue<>(capacity, capacity, maxConnections);

        this.requestNotifier = new RequestNotifier(client);
        this.responseNotifier = new ResponseNotifier(client);

        ProxyConfiguration proxyConfig = client.getProxyConfiguration();
        proxyAddress = proxyConfig != null && proxyConfig.matches(host, port) ?
                new Address(proxyConfig.getHost(), proxyConfig.getPort()) : null;

        if (!client.isDefaultPort(scheme, port))
            host += ":" + port;
        hostField = new HttpField(HttpHeader.HOST, host);
    }

    protected BlockingQueue<Connection> getIdleConnections()
    {
        return idleConnections;
    }

    protected BlockingQueue<Connection> getActiveConnections()
    {
        return activeConnections;
    }

    public RequestNotifier getRequestNotifier()
    {
        return requestNotifier;
    }

    public ResponseNotifier getResponseNotifier()
    {
        return responseNotifier;
    }

    @Override
    public String getScheme()
    {
        return scheme;
    }

    @Override
    public String getHost()
    {
        // InetSocketAddress.getHostString() transforms the host string
        // in case of IPv6 addresses, so we return the original host string
        return host;
    }

    @Override
    public int getPort()
    {
        return address.getPort();
    }

    public Address getConnectAddress()
    {
        return isProxied() ? proxyAddress : address;
    }

    public boolean isProxied()
    {
        return proxyAddress != null;
    }

    public HttpField getHostField()
    {
        return hostField;
    }

    public void send(Request request, List<Response.ResponseListener> listeners)
    {
        if (!scheme.equals(request.getScheme()))
            throw new IllegalArgumentException("Invalid request scheme " + request.getScheme() + " for destination " + this);
        if (!getHost().equals(request.getHost()))
            throw new IllegalArgumentException("Invalid request host " + request.getHost() + " for destination " + this);
        int port = request.getPort();
        if (port >= 0 && getPort() != port)
            throw new IllegalArgumentException("Invalid request port " + port + " for destination " + this);

        HttpConversation conversation = client.getConversation(request.getConversationID(), true);
        HttpExchange exchange = new HttpExchange(conversation, this, request, listeners);

        if (client.isRunning())
        {
            if (exchanges.offer(exchange))
            {
                if (!client.isRunning() && exchanges.remove(exchange))
                {
                    throw new RejectedExecutionException(client + " is stopping");
                }
                else
                {
                    LOG.debug("Queued {}", request);
                    requestNotifier.notifyQueued(request);
                    Connection connection = acquire();
                    if (connection != null)
                        process(connection, false);
                }
            }
            else
            {
                LOG.debug("Max queued exceeded {}", request);
                abort(exchange, new RejectedExecutionException("Max requests per destination " + client.getMaxRequestsQueuedPerDestination() + " exceeded for " + this));
            }
        }
        else
        {
            throw new RejectedExecutionException(client + " is stopped");
        }
    }

    public void newConnection(Promise<Connection> promise)
    {
        createConnection(new ProxyPromise(promise));
    }

    protected void createConnection(Promise<Connection> promise)
    {
        client.newConnection(this, promise);
    }

    protected Connection acquire()
    {
        Connection result = idleConnections.poll();
        if (result != null)
            return result;

        final int maxConnections = client.getMaxConnectionsPerDestination();
        while (true)
        {
            int current = connectionCount.get();
            final int next = current + 1;

            if (next > maxConnections)
            {
                LOG.debug("Max connections per destination {} exceeded for {}", current, this);
                // Try again the idle connections
                return idleConnections.poll();
            }

            if (connectionCount.compareAndSet(current, next))
            {
                LOG.debug("Creating connection {}/{} for {}", next, maxConnections, this);

                // This is the promise that is being called when a connection (eventually proxied) succeeds or fails.
                Promise<Connection> promise = new Promise<Connection>()
                {
                    @Override
                    public void succeeded(Connection connection)
                    {
                        process(connection, true);
                    }

                    @Override
                    public void failed(final Throwable x)
                    {
                        client.getExecutor().execute(new Runnable()
                        {
                            @Override
                            public void run()
                            {
                                abort(x);
                            }
                        });
                    }
                };

                // Create a new connection, and pass a ProxyPromise to establish a proxy tunnel, if needed.
                // Differently from the case where the connection is created explicitly by applications, here
                // we need to do a bit more logging and keep track of the connection count in case of failures.
                createConnection(new ProxyPromise(promise)
                {
                    @Override
                    public void succeeded(Connection connection)
                    {
                        LOG.debug("Created connection {}/{} {} for {}", next, maxConnections, connection, HttpDestination.this);
                        super.succeeded(connection);
                    }

                    @Override
                    public void failed(Throwable x)
                    {
                        LOG.debug("Connection failed {} for {}", x, HttpDestination.this);
                        connectionCount.decrementAndGet();
                        super.failed(x);
                    }
                });

                // Try again the idle connections
                return idleConnections.poll();
            }
        }
    }

    private void abort(Throwable cause)
    {
        HttpExchange exchange;
        while ((exchange = exchanges.poll()) != null)
            abort(exchange, cause);
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
    protected void process(Connection connection, boolean dispatch)
    {
        // Ugly cast, but lack of generic reification forces it
        final HttpConnection httpConnection = (HttpConnection)connection;

        final HttpExchange exchange = exchanges.poll();
        if (exchange == null)
        {
            LOG.debug("{} idle", httpConnection);
            if (!idleConnections.offer(httpConnection))
            {
                LOG.debug("{} idle overflow");
                httpConnection.close();
            }
            if (!client.isRunning())
            {
                LOG.debug("{} is stopping", client);
                remove(httpConnection);
                httpConnection.close();
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
                LOG.debug("{} active", httpConnection);
                if (!activeConnections.offer(httpConnection))
                {
                    LOG.warn("{} active overflow");
                }
                if (dispatch)
                {
                    client.getExecutor().execute(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            httpConnection.send(exchange);
                        }
                    });
                }
                else
                {
                    httpConnection.send(exchange);
                }
            }
        }
    }

    public void release(Connection connection)
    {
        LOG.debug("{} released", connection);
        if (client.isRunning())
        {
            boolean removed = activeConnections.remove(connection);
            if (removed)
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

    public void remove(Connection connection)
    {
        boolean removed = activeConnections.remove(connection);
        removed |= idleConnections.remove(connection);
        if (removed)
        {
            int open = connectionCount.decrementAndGet();
            LOG.debug("Removed connection {} for {} - open: {}", connection, this, open);
        }

        // We need to execute queued requests even if this connection failed.
        // We may create a connection that is not needed, but it will eventually
        // idle timeout, so no worries
        if (!exchanges.isEmpty())
        {
            connection = acquire();
            if (connection != null)
                process(connection, false);
        }
    }

    public void close()
    {
        for (Connection connection : idleConnections)
            connection.close();
        idleConnections.clear();

        // A bit drastic, but we cannot wait for all requests to complete
        for (Connection connection : activeConnections)
            connection.close();
        activeConnections.clear();

        abort(new AsynchronousCloseException());

        connectionCount.set(0);

        LOG.debug("Closed {}", this);
    }

    public boolean remove(HttpExchange exchange)
    {
        return exchanges.remove(exchange);
    }

    protected void abort(HttpExchange exchange, Throwable cause)
    {
        Request request = exchange.getRequest();
        HttpResponse response = exchange.getResponse();
        getRequestNotifier().notifyFailure(request, cause);
        List<Response.ResponseListener> listeners = exchange.getConversation().getResponseListeners();
        getResponseNotifier().notifyFailure(listeners, response, cause);
        getResponseNotifier().notifyComplete(listeners, new Result(request, cause, response, cause));
    }

    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        ContainerLifeCycle.dumpObject(out, this + " - requests queued: " + exchanges.size());
        List<String> connections = new ArrayList<>();
        for (Connection connection : idleConnections)
            connections.add(connection + " - IDLE");
        for (Connection connection : activeConnections)
            connections.add(connection + " - ACTIVE");
        ContainerLifeCycle.dump(out, indent, connections);
    }

    @Override
    public String toString()
    {
        return String.format("%s(%s://%s:%d)%s",
                HttpDestination.class.getSimpleName(),
                getScheme(),
                getHost(),
                getPort(),
                proxyAddress == null ? "" : " via " + proxyAddress.getHost() + ":" + proxyAddress.getPort());
    }

    /**
     * Decides whether to establish a proxy tunnel using HTTP CONNECT.
     * It is implemented as a promise because it needs to establish the tunnel
     * when the TCP connection is succeeded, and needs to notify another
     * promise when the tunnel is established (or failed).
     */
    private class ProxyPromise implements Promise<Connection>
    {
        private final Promise<Connection> delegate;

        private ProxyPromise(Promise<Connection> delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public void succeeded(Connection connection)
        {
            boolean tunnel = isProxied() &&
                    HttpScheme.HTTPS.is(getScheme()) &&
                    client.getSslContextFactory() != null;
            if (tunnel)
                tunnel(connection);
            else
                delegate.succeeded(connection);
        }

        @Override
        public void failed(Throwable x)
        {
            delegate.failed(x);
        }

        private void tunnel(final Connection connection)
        {
            String target = address.getHost() + ":" + address.getPort();
            Request connect = client.newRequest(proxyAddress.getHost(), proxyAddress.getPort())
                    .scheme(HttpScheme.HTTP.asString())
                    .method(HttpMethod.CONNECT)
                    .path(target)
                    .header(HttpHeader.HOST.asString(), target)
                    .timeout(client.getConnectTimeout(), TimeUnit.MILLISECONDS);
            connection.send(connect, new Response.CompleteListener()
            {
                @Override
                public void onComplete(Result result)
                {
                    if (result.isFailed())
                    {
                        failed(result.getFailure());
                        connection.close();
                    }
                    else
                    {
                        Response response = result.getResponse();
                        if (response.getStatus() == 200)
                        {
                            delegate.succeeded(connection);
                        }
                        else
                        {
                            failed(new HttpResponseException("Received " + response + " for " + result.getRequest(), response));
                            connection.close();
                        }
                    }
                }
            });
        }
    }
}
