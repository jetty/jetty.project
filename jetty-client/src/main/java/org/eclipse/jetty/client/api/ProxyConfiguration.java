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

package org.eclipse.jetty.client.api;

import java.util.HashSet;
import java.util.Set;

/**
 * The configuration of the forward proxy to use with {@link org.eclipse.jetty.client.HttpClient}.
 * <p />
 * Configuration parameters include the host and port of the forward proxy, and a list of
 * {@link #getExcludedOrigins() origins} that are excluded from being proxied.
 *
 * @see org.eclipse.jetty.client.HttpClient#setProxyConfiguration(ProxyConfiguration)
 */
public class ProxyConfiguration
{
    private final Set<String> excluded = new HashSet<>();
    private final String host;
    private final int port;

    public ProxyConfiguration(String host, int port)
    {
        this.host = host;
        this.port = port;
    }

    /**
     * @return the host name of the forward proxy
     */
    public String getHost()
    {
        return host;
    }

    /**
     * @return the port of the forward proxy
     */
    public int getPort()
    {
        return port;
    }

    /**
     * Matches the given {@code host} and {@code port} with the list of excluded origins,
     * returning true if the origin is to be proxied, false if it is excluded from proxying.
     * @param host the host to match
     * @param port the port to match
     * @return true if the origin made of {@code host} and {@code port} is to be proxied,
     * false if it is excluded from proxying.
     */
    public boolean matches(String host, int port)
    {
        String hostPort = host + ":" + port;
        return !getExcludedOrigins().contains(hostPort);
    }

    /**
     * @return the list of origins to exclude from proxying, in the form "host:port".
     */
    public Set<String> getExcludedOrigins()
    {
        return excluded;
    }
}
