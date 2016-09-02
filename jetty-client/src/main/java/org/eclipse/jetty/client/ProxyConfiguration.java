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

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.util.HostPort;

/**
 * The configuration of the forward proxy to use with {@link org.eclipse.jetty.client.HttpClient}.
 * <p>
 * Applications add subclasses of {@link Proxy} to this configuration via:
 * <pre>
 * ProxyConfiguration proxyConfig = httpClient.getProxyConfiguration();
 * proxyConfig.getProxies().add(new HttpProxy(proxyHost, 8080));
 * </pre>
 *
 * @see HttpClient#getProxyConfiguration()
 */
public class ProxyConfiguration
{
    private final List<Proxy> proxies = new ArrayList<>();

    public List<Proxy> getProxies()
    {
        return proxies;
    }

    public Proxy match(Origin origin)
    {
        for (Proxy proxy : getProxies())
        {
            if (proxy.matches(origin))
                return proxy;
        }
        return null;
    }

    public static abstract class Proxy
    {
        // TO use IPAddress Map
        private final Set<String> included = new HashSet<>();
        private final Set<String> excluded = new HashSet<>();
        private final Origin.Address address;
        private final boolean secure;

        protected Proxy(Origin.Address address, boolean secure)
        {
            this.address = address;
            this.secure = secure;
        }

        /**
         * @return the address of this proxy
         */
        public Origin.Address getAddress()
        {
            return address;
        }

        /**
         * @return whether the connection to the proxy must be secured via TLS
         */
        public boolean isSecure()
        {
            return secure;
        }

        /**
         * @return the list of origins that must be proxied
         * @see #matches(Origin)
         * @see #getExcludedAddresses()
         */
        public Set<String> getIncludedAddresses()
        {
            return included;
        }

        /**
         * @return the list of origins that must not be proxied.
         * @see #matches(Origin)
         * @see #getIncludedAddresses()
         */
        public Set<String> getExcludedAddresses()
        {
            return excluded;
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

            boolean result = included.isEmpty();
            Origin.Address address = origin.getAddress();
            for (String included : this.included)
            {
                if (matches(address, included))
                {
                    result = true;
                    break;
                }
            }
            for (String excluded : this.excluded)
            {
                if (matches(address, excluded))
                {
                    result = false;
                    break;
                }
            }
            return result;
        }

        private boolean matches(Origin.Address address, String pattern)
        {
            // TODO: add support for CIDR notation like 192.168.0.0/24, see DoSFilter
            HostPort hostPort = new HostPort(pattern);
            String host = hostPort.getHost();
            int port = hostPort.getPort();
            return host.equals(address.getHost())  && ( port<=0 || port==address.getPort() ); 
        }

        /**
         * @param connectionFactory the nested {@link ClientConnectionFactory}
         * @return a new {@link ClientConnectionFactory} for this {@link Proxy}
         */
        public abstract ClientConnectionFactory newClientConnectionFactory(ClientConnectionFactory connectionFactory);

        @Override
        public String toString()
        {
            return address.toString();
        }
    }

}
