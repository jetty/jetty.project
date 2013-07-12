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

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.AsynchronousCloseException;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

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
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.ssl.SslContextFactory;

public abstract class HttpDestination implements Destination, Closeable, Dumpable
{
    protected static final Logger LOG = Log.getLogger(new Object(){}.getClass().getEnclosingClass());

    private final HttpClient client;
    private final String scheme;
    private final String host;
    private final Address address;
    private final Queue<HttpExchange> exchanges;
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

        this.exchanges = new LinkedBlockingQueue<>(client.getMaxRequestsQueuedPerDestination());

        this.requestNotifier = new RequestNotifier(client);
        this.responseNotifier = new ResponseNotifier(client);

        ProxyConfiguration proxyConfig = client.getProxyConfiguration();
        proxyAddress = proxyConfig != null && proxyConfig.matches(host, port) ?
                new Address(proxyConfig.getHost(), proxyConfig.getPort()) : null;

        if (!client.isDefaultPort(scheme, port))
            host += ":" + port;
        hostField = new HttpField(HttpHeader.HOST, host);
    }

    public HttpClient getHttpClient()
    {
        return client;
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

    public URI getProxyURI()
    {
        ProxyConfiguration proxyConfiguration = client.getProxyConfiguration();
        String uri = getScheme() + "://" + proxyConfiguration.getHost();
        if (!client.isDefaultPort(getScheme(), proxyConfiguration.getPort()))
            uri += ":" + proxyConfiguration.getPort();
        return URI.create(uri);
    }

    public HttpField getHostField()
    {
        return hostField;
    }

    protected void send(Request request, List<Response.ResponseListener> listeners)
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
                    send();
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

    protected abstract void send();

    public void newConnection(Promise<Connection> promise)
    {
        createConnection(new ProxyPromise(promise));
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
        LOG.debug("Closed {}", this);
    }

    /**
     * Aborts all the {@link HttpExchange}s queued in this destination.
     *
     * @param cause the abort cause
     * @see #abort(HttpExchange, Throwable)
     */
    public void abort(Throwable cause)
    {
        HttpExchange exchange;
        while ((exchange = exchanges.poll()) != null)
            abort(exchange, cause);
    }

    /**
     * Aborts the given {@code exchange}, notifies listeners of the failure, and completes the exchange.
     *
     * @param exchange the {@link HttpExchange} to abort
     * @param cause the abort cause
     */
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
    }

    public String asString()
    {
        return client.address(getScheme(), getHost(), getPort());
    }

    @Override
    public String toString()
    {
        return String.format("%s(%s)%s",
                HttpDestination.class.getSimpleName(),
                asString(),
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
            if (isProxied() && HttpScheme.HTTPS.is(getScheme()))
            {
                if (client.getSslContextFactory() != null)
                {
                    tunnel(connection);
                }
                else
                {
                    String message = String.format("Cannot perform requests over SSL, no %s in %s",
                            SslContextFactory.class.getSimpleName(), HttpClient.class.getSimpleName());
                    delegate.failed(new IllegalStateException(message));
                }
            }
            else
            {
                delegate.succeeded(connection);
            }
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
                    .header(HttpHeader.HOST, target)
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
                            // Wrap the connection with TLS
                            Connection tunnel = client.tunnel(connection);
                            delegate.succeeded(tunnel);
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
