//========================================================================
//Copyright 2012-2012 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses.
//========================================================================

package org.eclipse.jetty.client;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.sun.jndi.toolkit.url.Uri;
import org.eclipse.jetty.client.api.Address;
import org.eclipse.jetty.client.api.Connection;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.io.AsyncConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.SelectChannelEndPoint;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.component.AggregateLifeCycle;

/**
 * <p>{@link HTTPClient} provides an asynchronous non-blocking implementation to perform HTTP requests to a server.</p>
 * <p>{@link HTTPClient} provides easy-to-use methods such as {@link #GET(String)} that allow to perform HTTP
 * requests in a one-liner, but also gives the ability to fine tune the configuration of requests via
 * {@link Request.Builder}.</p>
 * <p>{@link HTTPClient} acts as a central configuration point for network parameters (such as idle timeouts) and
 * HTTP parameters (such as whether to follow redirects).</p>
 * <p>{@link HTTPClient} transparently pools connections to servers, but allows direct control of connections for
 * cases where this is needed.</p>
 * <p>Typical usage:</p>
 * <pre>
 * // One liner:
 * new HTTPClient().GET("http://localhost:8080/").get().getStatus();
 *
 * // Using the builder with a timeout
 * HTTPClient client = new HTTPClient();
 * Response response = client.builder("http://localhost:8080/").build().send().get(5, TimeUnit.SECONDS);
 * int status = response.getStatus();
 *
 * // Asynchronously
 * HTTPClient client = new HTTPClient();
 * client.builder("http://localhost:8080/").build().send(new Response.Listener.Adapter()
 * {
 *     &#64;Override
 *     public void onComplete(Response response)
 *     {
 *         ...
 *     }
 * });
 * </pre>
 */
public class HTTPClient extends AggregateLifeCycle
{
    private final ConcurrentMap<String, Destination> destinations = new ConcurrentHashMap<>();
    private volatile String agent = "Jetty/" + Jetty.VERSION;
    private volatile boolean followRedirects = true;
    private volatile Executor executor;
    private volatile int maxConnectionsPerAddress = Integer.MAX_VALUE;
    private volatile int maxQueueSizePerAddress = Integer.MAX_VALUE;
    private volatile SocketAddress bindAddress;
    private volatile SelectorManager selectorManager;
    private volatile long idleTimeout;

    @Override
    protected void doStart() throws Exception
    {
        selectorManager = newSelectorManager();
        addBean(selectorManager);
        super.doStart();
    }

    protected SelectorManager newSelectorManager()
    {
        ClientSelectorManager result = new ClientSelectorManager();
        result.setMaxIdleTime(getIdleTimeout());
        return result;
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
    }

    public long getIdleTimeout()
    {
        return idleTimeout;
    }

    public void setIdleTimeout(long idleTimeout)
    {
        this.idleTimeout = idleTimeout;
    }

    /**
     * @return the address to bind socket channels to
     * @see #setBindAddress(SocketAddress)
     */
    public SocketAddress getBindAddress()
    {
        return bindAddress;
    }

    /**
     * @param bindAddress the address to bind socket channels to
     * @see #getBindAddress()
     */
    public void setBindAddress(SocketAddress bindAddress)
    {
        this.bindAddress = bindAddress;
    }

    public Future<Response> GET(String uri)
    {
        return GET(URI.create(uri));
    }

    public Future<Response> GET(URI uri)
    {
        return builder(uri)
                .method("GET")
                // Add decoder, cookies, agent, default headers, etc.
                .agent(getUserAgent())
                .followRedirects(isFollowRedirects())
                .build()
                .send();
    }

    public Request.Builder builder(String uri)
    {
        return builder(URI.create(uri));
    }

    public Request.Builder builder(URI uri)
    {
        return new StandardRequest(this, uri);
    }

    public Request.Builder builder(Request prototype)
    {
        return null;
    }

    public Destination getDestination(String address)
    {
        Destination destination = destinations.get(address);
        if (destination == null)
        {
            destination = new StandardDestination(this, address);
            Destination existing = destinations.putIfAbsent(address, destination);
            if (existing != null)
                destination = existing;
        }
        return destination;
    }

    public String getUserAgent()
    {
        return agent;
    }

    public void setUserAgent(String agent)
    {
        this.agent = agent;
    }

    public boolean isFollowRedirects()
    {
        return followRedirects;
    }

    public void setFollowRedirects(boolean follow)
    {
        this.followRedirects = follow;
    }

    public void join()
    {

    }

    public void join(long timeout, TimeUnit unit)
    {
    }

    public Future<Response> send(Request request, Response.Listener listener)
    {
        URI uri = request.uri();
        String scheme = uri.getScheme();
        if (!Arrays.asList("http", "https").contains(scheme.toLowerCase()))
            throw new IllegalArgumentException("Invalid protocol " + scheme);

        String key = scheme.toLowerCase() + "://" + uri.getHost().toLowerCase();
        int port = uri.getPort();
        if (port < 0)
            key += "https".equalsIgnoreCase(scheme) ? ":443" : ":80";

        return getDestination(key).send(request, listener);
    }

    public Executor getExecutor()
    {
        return executor;
    }

    public int getMaxConnectionsPerAddress()
    {
        return maxConnectionsPerAddress;
    }

    public void setMaxConnectionsPerAddress(int maxConnectionsPerAddress)
    {
        this.maxConnectionsPerAddress = maxConnectionsPerAddress;
    }

    public int getMaxQueueSizePerAddress()
    {
        return maxQueueSizePerAddress;
    }

    public void setMaxQueueSizePerAddress(int maxQueueSizePerAddress)
    {
        this.maxQueueSizePerAddress = maxQueueSizePerAddress;
    }

    protected Future<Connection> newConnection(Destination destination) throws IOException
    {
        SocketChannel channel = SocketChannel.open();
        SocketAddress bindAddress = getBindAddress();
        if (bindAddress != null)
            channel.bind(bindAddress);
        channel.socket().setTcpNoDelay(true);
        channel.connect(destination.address().toSocketAddress());

        FutureCallback<Connection> result = new FutureCallback<>();
        selectorManager.connect(channel, result);
        return result;
    }

    protected class ClientSelectorManager extends SelectorManager
    {
        public ClientSelectorManager()
        {
            this(1);
        }

        public ClientSelectorManager(int selectors)
        {
            super(selectors);
        }

        @Override
        protected Selectable newEndPoint(SocketChannel channel, ManagedSelector selectSet, SelectionKey sKey) throws IOException
        {
            return new SelectChannelEndPoint(channel, selectSet, sKey, getMaxIdleTime());
        }

        @Override
        public AsyncConnection newConnection(SocketChannel channel, AsyncEndPoint endpoint, Object attachment)
        {
            // TODO: SSL

            return new StandardConnection(channel, endpoint, )
        }

        @Override
        protected void execute(Runnable task)
        {
            getExecutor().execute(task);
        }
    }
}
