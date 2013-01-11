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
import java.net.InetSocketAddress;
import java.nio.channels.AsynchronousCloseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.ProxyConfiguration;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.client.util.TimedResponseListener;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.FuturePromise;
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
    private final InetSocketAddress address;
    private final Queue<RequestContext> requests;
    private final BlockingQueue<Connection> idleConnections;
    private final BlockingQueue<Connection> activeConnections;
    private final RequestNotifier requestNotifier;
    private final ResponseNotifier responseNotifier;
    private final InetSocketAddress proxyAddress;
    private final HttpField hostField;

    public HttpDestination(HttpClient client, String scheme, String host, int port)
    {
        this.client = client;
        this.scheme = scheme;
        this.host = host;
        this.address = new InetSocketAddress(host, port);

        int maxRequestsQueued = client.getMaxRequestsQueuedPerDestination();
        int capacity = Math.min(32, maxRequestsQueued);
        this.requests = new BlockingArrayQueue<>(capacity, capacity, maxRequestsQueued);

        int maxConnections = client.getMaxConnectionsPerDestination();
        capacity = Math.min(8, maxConnections);
        this.idleConnections = new BlockingArrayQueue<>(capacity, capacity, maxConnections);
        this.activeConnections = new BlockingArrayQueue<>(capacity, capacity, maxConnections);

        this.requestNotifier = new RequestNotifier(client);
        this.responseNotifier = new ResponseNotifier(client);

        ProxyConfiguration proxyConfig = client.getProxyConfiguration();
        proxyAddress = proxyConfig != null && proxyConfig.matches(host, port) ?
                new InetSocketAddress(proxyConfig.getHost(), proxyConfig.getPort()) : null;

        String hostValue = host;
        if ("https".equalsIgnoreCase(scheme) && port != 443 ||
                "http".equalsIgnoreCase(scheme) && port != 80)
            hostValue += ":" + port;
        hostField = new HttpField(HttpHeader.HOST, hostValue);
    }

    protected BlockingQueue<Connection> getIdleConnections()
    {
        return idleConnections;
    }

    protected BlockingQueue<Connection> getActiveConnections()
    {
        return activeConnections;
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

    public InetSocketAddress getConnectAddress()
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

        RequestContext requestContext = new RequestContext(request, listeners);
        if (client.isRunning())
        {
            if (requests.offer(requestContext))
            {
                if (!client.isRunning() && requests.remove(requestContext))
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
                throw new RejectedExecutionException("Max requests per destination " + client.getMaxRequestsQueuedPerDestination() + " exceeded");
            }
        }
        else
        {
            throw new RejectedExecutionException(client + " is stopped");
        }
    }

    public Future<Connection> newConnection()
    {
        FuturePromise<Connection> result = new FuturePromise<>();
        newConnection(new ProxyPromise(result));
        return result;
    }

    protected void newConnection(Promise<Connection> promise)
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
                LOG.debug("Max connections {} reached for {}", current, this);
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
                                drain(x);
                            }
                        });
                    }
                };

                // Create a new connection, and pass a ProxyPromise to establish a proxy tunnel, if needed.
                // Differently from the case where the connection is created explicitly by applications, here
                // we need to do a bit more logging and keep track of the connection count in case of failures.
                newConnection(new ProxyPromise(promise)
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

    private void drain(Throwable x)
    {
        RequestContext requestContext;
        while ((requestContext = requests.poll()) != null)
        {
            Request request = requestContext.request;
            requestNotifier.notifyFailure(request, x);
            List<Response.ResponseListener> listeners = requestContext.listeners;
            HttpResponse response = new HttpResponse(request, listeners);
            responseNotifier.notifyFailure(listeners, response, x);
            responseNotifier.notifyComplete(listeners, new Result(request, x, response, x));
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
     */
    protected void process(Connection connection, boolean dispatch)
    {
        // Ugly cast, but lack of generic reification forces it
        final HttpConnection httpConnection = (HttpConnection)connection;

        RequestContext requestContext = requests.poll();
        if (requestContext == null)
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
            final Request request = requestContext.request;
            final List<Response.ResponseListener> listeners = requestContext.listeners;
            Throwable cause = request.getAbortCause();
            if (cause != null)
            {
                abort(request, listeners, cause);
                LOG.debug("Aborted {} before processing", request);
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
                            httpConnection.send(request, listeners);
                        }
                    });
                }
                else
                {
                    httpConnection.send(request, listeners);
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
        if (!requests.isEmpty())
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

        drain(new AsynchronousCloseException());

        connectionCount.set(0);

        LOG.debug("Closed {}", this);
    }

    public boolean abort(Request request, Throwable cause)
    {
        for (RequestContext requestContext : requests)
        {
            if (requestContext.request == request)
            {
                if (requests.remove(requestContext))
                {
                    // We were able to remove the pair, so it won't be processed
                    abort(request, requestContext.listeners, cause);
                    LOG.debug("Aborted {} while queued", request);
                    return true;
                }
            }
        }
        return false;
    }

    private void abort(Request request, List<Response.ResponseListener> listeners, Throwable cause)
    {
        HttpResponse response = new HttpResponse(request, listeners);
        responseNotifier.notifyFailure(listeners, response, cause);
        responseNotifier.notifyComplete(listeners, new Result(request, cause, response, cause));
    }

    @Override
    public String dump()
    {
        return ContainerLifeCycle.dump(this);
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        ContainerLifeCycle.dumpObject(out, this + " - requests queued: " + requests.size());
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
                proxyAddress == null ? "" : " via " + proxyAddress.getHostString() + ":" + proxyAddress.getPort());
    }

    private static class RequestContext
    {
        private final Request request;
        private final List<Response.ResponseListener> listeners;

        private RequestContext(Request request, List<Response.ResponseListener> listeners)
        {
            this.request = request;
            this.listeners = listeners;
        }
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
                    "https".equalsIgnoreCase(getScheme()) &&
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
            String target = address.getHostString() + ":" + address.getPort();
            Request connect = client.newRequest(proxyAddress.getHostString(), proxyAddress.getPort())
                    .scheme(HttpScheme.HTTP.asString())
                    .method(HttpMethod.CONNECT)
                    .path(target)
                    .header(HttpHeader.HOST.asString(), target);
            connection.send(connect, new TimedResponseListener(client.getConnectTimeout(), TimeUnit.MILLISECONDS, connect, new Response.CompleteListener()
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
            }));
        }
    }
}
