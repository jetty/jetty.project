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

import java.io.Closeable;
import java.io.IOException;
import java.nio.channels.AsynchronousCloseException;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.RejectedExecutionException;

import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.ssl.SslClientConnectionFactory;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public abstract class HttpDestination implements Destination, Closeable, Dumpable
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

    public HttpDestination(HttpClient client, Origin origin)
    {
        this.client = client;
        this.origin = origin;

        this.exchanges = new BlockingArrayQueue<>(client.getMaxRequestsQueuedPerDestination());

        this.requestNotifier = new RequestNotifier(client);
        this.responseNotifier = new ResponseNotifier();

        ProxyConfiguration proxyConfig = client.getProxyConfiguration();
        proxy = proxyConfig.match(origin);
        ClientConnectionFactory connectionFactory = client.getTransport();
        if (proxy != null)
        {
            connectionFactory = proxy.newClientConnectionFactory(connectionFactory);
        }
        else
        {
            if (HttpScheme.HTTPS.is(getScheme()))
                connectionFactory = newSslClientConnectionFactory(connectionFactory);
        }
        this.connectionFactory = connectionFactory;

        String host = getHost();
        if (!client.isDefaultPort(getScheme(), getPort()))
            host += ":" + getPort();
        hostField = new HttpField(HttpHeader.HOST, host);
    }

    protected ClientConnectionFactory newSslClientConnectionFactory(ClientConnectionFactory connectionFactory)
    {
        return new SslClientConnectionFactory(client.getSslContextFactory(), client.getByteBufferPool(), client.getExecutor(), connectionFactory);
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
    public String getScheme()
    {
        return origin.getScheme();
    }

    @Override
    public String getHost()
    {
        // InetSocketAddress.getHostString() transforms the host string
        // in case of IPv6 addresses, so we return the original host string
        return origin.getAddress().getHost();
    }

    @Override
    public int getPort()
    {
        return origin.getAddress().getPort();
    }

    public Origin.Address getConnectAddress()
    {
        return proxy == null ? origin.getAddress() : proxy.getAddress();
    }

    public HttpField getHostField()
    {
        return hostField;
    }

    protected void send(HttpRequest request, List<Response.ResponseListener> listeners)
    {
        if (!getScheme().equals(request.getScheme()))
            throw new IllegalArgumentException("Invalid request scheme " + request.getScheme() + " for destination " + this);
        if (!getHost().equals(request.getHost()))
            throw new IllegalArgumentException("Invalid request host " + request.getHost() + " for destination " + this);
        int port = request.getPort();
        if (port >= 0 && getPort() != port)
            throw new IllegalArgumentException("Invalid request port " + port + " for destination " + this);

        HttpExchange exchange = new HttpExchange(this, request, listeners);

        if (client.isRunning())
        {
            if (exchanges.offer(exchange))
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

    protected abstract void send();

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
    }

    public void release(Connection connection)
    {
    }

    public void close(Connection connection)
    {
    }

    /**
     * Aborts all the {@link HttpExchange}s queued in this destination.
     *
     * @param cause the abort cause
     */
    public void abort(Throwable cause)
    {
        HttpExchange exchange;
        // Just peek(), the abort() will remove it from the queue.
        while ((exchange = exchanges.peek()) != null)
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
        ContainerLifeCycle.dumpObject(out, this + " - requests queued: " + exchanges.size());
    }

    public String asString()
    {
        return origin.asString();
    }

    @Override
    public String toString()
    {
        return String.format("%s[%s]%s,queue=%d",
                HttpDestination.class.getSimpleName(),
                asString(),
                proxy == null ? "" : "(via " + proxy + ")",
                exchanges.size());
    }
}
