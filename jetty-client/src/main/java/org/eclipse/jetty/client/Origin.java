//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.client.dynamic.HttpClientTransportDynamic;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.URIUtil;

/**
 * <p>Class that groups the elements that uniquely identify a destination.</p>
 * <p>The elements are {@code scheme}, {@code host}, {@code port}, a
 * {@link Origin.Protocol} and a tag object that further distinguishes
 * destinations that have the same origin and protocol.</p>
 * <p>In general it is possible that, for the same origin, the server can
 * speak different protocols (for example, clear-text HTTP/1.1 and clear-text
 * HTTP/2), so the {@link Origin.Protocol} makes that distinction.</p>
 * <p>Furthermore, it may be desirable to have different destinations for
 * the same origin and protocol (for example, when using the PROXY protocol
 * in a reverse proxy server, you want to be able to map the client ip:port
 * to the destination {@code tag}, so that all the connections to the server
 * associated to that destination can specify the PROXY protocol bytes for
 * that particular client connection.</p>
 */
public class Origin
{
    private final String scheme;
    private final Address address;
    private final Object tag;
    private final Protocol protocol;

    public Origin(String scheme, String host, int port)
    {
        this(scheme, host, port, null);
    }

    public Origin(String scheme, String host, int port, Object tag)
    {
        this(scheme, new Address(host, port), tag);
    }

    public Origin(String scheme, String host, int port, Object tag, Protocol protocol)
    {
        this(scheme, new Address(host, port), tag, protocol);
    }

    public Origin(String scheme, Address address)
    {
        this(scheme, address, null);
    }

    public Origin(String scheme, Address address, Object tag)
    {
        this(scheme, address, tag, null);
    }

    public Origin(String scheme, Address address, Object tag, Protocol protocol)
    {
        this.scheme = Objects.requireNonNull(scheme);
        this.address = address;
        this.tag = tag;
        this.protocol = protocol;
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

    public Protocol getProtocol()
    {
        return protocol;
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
            Objects.equals(tag, that.tag) &&
            Objects.equals(protocol, that.protocol);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(scheme, address, tag, protocol);
    }

    public String asString()
    {
        StringBuilder result = new StringBuilder();
        URIUtil.appendSchemeHostPort(result, scheme, address.host, address.port);
        return result.toString();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[%s,tag=%s,protocol=%s]",
            getClass().getSimpleName(),
            hashCode(),
            asString(),
            getTag(),
            getProtocol());
    }

    public static class Address
    {
        private final String host;
        private final int port;
        private final SocketAddress address;

        public Address(String host, int port)
        {
            this.host = HostPort.normalizeHost(Objects.requireNonNull(host));
            this.port = port;
            this.address = InetSocketAddress.createUnresolved(getHost(), getPort());
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
            return Objects.hash(host, port);
        }

        public String asString()
        {
            return String.format("%s:%d", host, port);
        }

        public SocketAddress getSocketAddress()
        {
            return address;
        }

        @Override
        public String toString()
        {
            return asString();
        }
    }

    /**
     * <p>The representation of a network protocol.</p>
     * <p>A network protocol may have multiple protocol <em>names</em>
     * associated to it, for example {@code ["h2", "h2-17", "h2-16"]}.</p>
     * <p>A Protocol is then rendered into a {@link ClientConnectionFactory}
     * chain, for example in
     * {@link HttpClientTransportDynamic#newConnection(EndPoint, Map)}.</p>
     */
    public static class Protocol
    {
        private final List<String> protocols;
        private final boolean negotiate;

        /**
         * Creates a Protocol with the given list of protocol names
         * and whether it should negotiate the protocol.
         *
         * @param protocols the protocol names
         * @param negotiate whether the protocol should be negotiated
         */
        public Protocol(List<String> protocols, boolean negotiate)
        {
            this.protocols = protocols;
            this.negotiate = negotiate;
        }

        public List<String> getProtocols()
        {
            return protocols;
        }

        public boolean isNegotiate()
        {
            return negotiate;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj == null || getClass() != obj.getClass())
                return false;
            Protocol that = (Protocol)obj;
            return protocols.equals(that.protocols) && negotiate == that.negotiate;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(protocols, negotiate);
        }

        public String asString()
        {
            return String.format("proto=%s,nego=%b", protocols, negotiate);
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[%s]", getClass().getSimpleName(), hashCode(), asString());
        }
    }
}
