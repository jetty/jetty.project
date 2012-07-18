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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.api.Address;
import org.eclipse.jetty.client.api.Destination;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.io.SelectorManager;
import org.eclipse.jetty.util.Jetty;
import org.eclipse.jetty.util.Name;
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
 * Response response = client.builder("localhost:8080").path("/").build().send().get(5, TimeUnit.SECONDS);
 * int status = response.getStatus();
 *
 * // Asynchronously
 * HTTPClient client = new HTTPClient();
 * client.builder("localhost:8080").path("/").build().send(new Response.Listener.Adapter()
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
    private final ConcurrentMap<Address, Destination> destinations = new ConcurrentHashMap<>();
    private volatile String agent = "Jetty/" + Jetty.VERSION;
    private volatile boolean followRedirects;
    private Executor executor;
    private int maxConnectionsPerAddress = Integer.MAX_VALUE;

    @Override
    protected void doStart() throws Exception
    {
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
    }

    public Future<Response> GET(String absoluteURL)
    {
        try
        {
            return GET(new URI(absoluteURL));
        }
        catch (URISyntaxException x)
        {
            throw new IllegalArgumentException(x);
        }
    }

    public Future<Response> GET(URI uri)
    {
        boolean secure = false;
        String scheme = uri.getScheme();
        if ("https".equals(scheme))
            secure = true;
        else if (!"http".equals(scheme))
            throw new IllegalArgumentException("Invalid scheme " + scheme);

        return builder(uri.getHost() + ":" + uri.getPort())
                .method("GET")
                .secure(secure)
                .path(uri.getPath())
                // Add decoder, cookies, agent, default headers, etc.
                .agent(getUserAgent())
                .followRedirects(isFollowRedirects())
                .build()
                .send();
    }

    public Request.Builder builder(String hostAndPort)
    {
        return builder(Address.from(hostAndPort));
    }

    private Request.Builder builder(Address address)
    {
        return new StandardRequest(this, address);
    }

    public Request.Builder builder(Request prototype)
    {
        return null;
    }

    public Destination getDestination(Address address)
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
        return getDestination(request.address()).send(request, listener);
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


    }
}
