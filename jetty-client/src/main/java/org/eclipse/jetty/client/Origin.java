//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.Objects;

import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.URIUtil;

public class Origin
{
    private final String scheme;
    private final Address address;
    private final Object tag;

    public Origin(String scheme, String host, int port)
    {
        this(scheme, host, port, null);
    }

    public Origin(String scheme, String host, int port, Object tag)
    {
        this(scheme, new Address(host, port), tag);
    }

    public Origin(String scheme, Address address)
    {
        this(scheme, address, null);
    }

    public Origin(String scheme, Address address, Object tag)
    {
        this.scheme = Objects.requireNonNull(scheme);
        this.address = address;
        this.tag = tag;
    }

    public String getScheme()
    {
        return scheme;
    }

    public Address getAddress()
    {
        return address;
    }

    public Object getTag()
    {
        return tag;
    }

    public String asString()
    {
        StringBuilder result = new StringBuilder();
        URIUtil.appendSchemeHostPort(result, scheme, address.host, address.port);
        return result.toString();
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        Origin that = (Origin)obj;
        return scheme.equals(that.scheme) &&
            address.equals(that.address) &&
            Objects.equals(tag, that.tag);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(scheme, address, tag);
    }

    @Override
    public String toString()
    {
        String result = asString();
        if (tag != null)
            result += "[tag=" + tag + "]";
        return result;
    }

    public static class Address
    {
        private final String host;
        private final int port;

        public Address(String host, int port)
        {
            this.host = HostPort.normalizeHost(Objects.requireNonNull(host));
            this.port = port;
        }

        public String getHost()
        {
            return host;
        }

        public int getPort()
        {
            return port;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            Address that = (Address)obj;
            return host.equals(that.host) && port == that.port;
        }

        @Override
        public int hashCode()
        {
            int result = host.hashCode();
            result = 31 * result + port;
            return result;
        }

        public String asString()
        {
            return String.format("%s:%d", host, port);
        }

        @Override
        public String toString()
        {
            return asString();
        }
    }
}
