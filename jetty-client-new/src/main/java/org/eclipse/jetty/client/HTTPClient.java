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

import java.util.concurrent.Future;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.api.Response;
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
 * Response response = client.builder("localhost:8080").uri("/").build().send().get(5, TimeUnit.SECONDS);
 * int status = response.getStatus();
 *
 * // Asynchronously
 * HTTPClient client = new HTTPClient();
 * client.builder("localhost:8080").uri("/").build().send(new Response.Listener.Adapter()
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
    public Future<Response> GET(String absoluteURL)
    {
        return null;
    }

    public Request.Builder builder(String hostAndPort)
    {
        return null;
    }

    public Request.Builder builder(Request prototype)
    {
        return null;
    }

    public Destination getDestination(String hostAndPort)
    {
        return null;
    }

    public void setFollowRedirects(boolean follow)
    {
    }

    public interface Destination
    {
        Connection newConnection();
    }

    public interface Connection extends AutoCloseable
    {
        Future<Response> send(Request request, Response.Listener listener);
    }
}
