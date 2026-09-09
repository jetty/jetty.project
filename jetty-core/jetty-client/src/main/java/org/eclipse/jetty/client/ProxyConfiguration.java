//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.HostPortPredicate;
import org.eclipse.jetty.util.HostPortSet;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * <p>The configuration of the forward proxies to use with
 * {@link org.eclipse.jetty.client.HttpClient}.</p>
 * <p>Applications add subclasses of {@link Proxy} to this
 * configuration via, for example:</p>
 * <pre>{@code
 * ProxyConfiguration proxyConfig = httpClient.getProxyConfiguration();
 * proxyConfig.addProxy(new HttpProxy(proxyHost, 8080));
 * }</pre>
 *
 * @see HttpClient#getProxyConfiguration()
 */
public class ProxyConfiguration
{
    private final List<Proxy> proxies = new BlockingArrayQueue<>();

    /**
     * <p>Returns an unmodifiable list of proxies added to this configuration.</p>
     *
     * @return the forward proxies
     * @see #addProxy(Proxy)
     */
    public List<Proxy> getProxies()
    {
        return List.copyOf(proxies);
    }

    /**
     * Adds a proxy.
     *
     * @param proxy a proxy
     * @throws NullPointerException if {@code proxy} is null
     */
    public void addProxy(Proxy proxy)
    {
        proxies.add(Objects.requireNonNull(proxy));
    }

    /**
     * Removes a proxy.
     *
     * @param proxy a proxy
     * @return true if a match is found
     */
    public boolean removeProxy(Proxy proxy)
    {
        return proxies.remove(proxy);
    }

    public Proxy match(Origin origin)
    {
        for (Proxy proxy : proxies)
        {
            if (proxy.matches(origin))
                return proxy;
        }
        return null;
    }

    public abstract static class Proxy
    {
        private final IncludeExcludeSet<String, HostPort> addresses = new IncludeExcludeSet<>(HostPortSet.class);
        private final Origin origin;
        private final SslContextFactory.Client sslContextFactory;

        protected Proxy(Origin.Address address, boolean secure, SslContextFactory.Client sslContextFactory, Origin.Protocol protocol)
        {
            this(new Origin(secure ? HttpScheme.HTTPS.asString() : HttpScheme.HTTP.asString(), address, null, protocol), sslContextFactory);
        }

        protected Proxy(Origin origin, SslContextFactory.Client sslContextFactory)
        {
            this.origin = origin;
            this.sslContextFactory = sslContextFactory;
        }

        public Origin getOrigin()
        {
            return origin;
        }

        /**
         * @return the address of this proxy
         */
        public Origin.Address getAddress()
        {
            return origin.getAddress();
        }

        /**
         * @return whether the connection to the proxy must be secured via TLS
         */
        public boolean isSecure()
        {
            return origin.isSecure();
        }

        /**
         * @return the optional SslContextFactory to use when connecting to proxies
         */
        public SslContextFactory.Client getSslContextFactory()
        {
            return sslContextFactory;
        }

        /**
         * @return the protocol spoken by this proxy
         */
        public Origin.Protocol getProtocol()
        {
            return origin.getProtocol();
        }

        /**
         * <p>Returns the set of origins that must be proxied. The set accepts the
         * patterns documented by {@link HostPortPredicate}.</p>
         *
         * @return the set of origins that must be proxied
         * @see #matches(Origin)
         * @see #getExcludedAddresses()
         */
        public Set<String> getIncludedAddresses()
        {
            return addresses.getIncluded();
        }

        /**
         * <p>Returns the set of origins that must not be proxied. The set accepts the
         * patterns documented by {@link HostPortPredicate}.</p>
         *
         * @return the set of origins that must not be proxied
         * @see #matches(Origin)
         * @see #getIncludedAddresses()
         */
        public Set<String> getExcludedAddresses()
        {
            return addresses.getExcluded();
        }

        /**
         * @return an URI representing this proxy, or null if no URI can represent this proxy
         */
        public URI getURI()
        {
            return null;
        }

        /**
         * Matches the given {@code origin} with the included and excluded addresses,
         * returning true if the given {@code origin} is to be proxied.
         *
         * @param origin the origin to test for proxying
         * @return true if the origin must be proxied, false otherwise
         */
        public boolean matches(Origin origin)
        {
            if (getAddress().equals(origin.getAddress()))
                return false;

            Origin.Address address = origin.getAddress();
            HostPort hostPort = new HostPort(address.getHost(), address.getPort());
            return addresses.test(hostPort);
        }

        /**
         * @param connectionFactory the nested {@link ClientConnectionFactory}
         * @return a new {@link ClientConnectionFactory} for this Proxy
         */
        public abstract ClientConnectionFactory newClientConnectionFactory(ClientConnectionFactory connectionFactory);

        @Override
        public String toString()
        {
            return origin.toString();
        }
    }
}
