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

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.ClientConnectionFactory;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.URIUtil;

/**
 * <p>Class that groups the elements that uniquely identify a destination.</p>
 * <p>The elements are {@code scheme}, {@code host}, {@code port}, a
 * {@link Origin.Protocol}, a tag object that further distinguishes
 * destinations that have the same scheme, host, port and protocol,
 * and a {@link Transport}.</p>
 * <p>In general it is possible that, for the same scheme, host and port,
 * the server can speak different protocols (for example, clear-text HTTP/1.1
 * and clear-text HTTP/2), so the {@link Origin.Protocol} makes that distinction.</p>
 * <p>Furthermore, it may be desirable to have different destinations for
 * the same scheme, host, port and protocol.
 * For example, when using the PROXY protocol in a reverse proxy server, you
 * may want to be able to map the client ip:port to the destination {@code tag},
 * so that all the connections to the server associated to that destination can
 * specify the PROXY protocol bytes for that particular client connection.</p>
 * <p>Finally, it is necessary to have different destinations for the same
 * scheme, host, port, and protocol, but having different {@link Transport},
 * for example when the same server may be reached via TCP/IP but also via
 * Unix-Domain sockets.</p>
 */
public class Origin
{
    private final String scheme;
    private final Address address;
    private final Object tag;
    private final Protocol protocol;
    private final Transport transport;

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
        this(scheme, address, tag, protocol, Transport.TCP_IP);
    }

    public Origin(String scheme, Address address, Object tag, Protocol protocol, Transport transport)
    {
        this.scheme = URIUtil.normalizeScheme(Objects.requireNonNull(scheme));
        this.address = address;
        this.tag = tag;
        this.protocol = protocol;
        this.transport = transport;
    }

    /**
     * @return the URI scheme (http, https, etc.)
     */
    public String getScheme()
    {
        return scheme;
    }

    /**
     * @return the network address (host and port)
     */
    public Address getAddress()
    {
        return address;
    }

    /**
     * @return the tag object that distinguishes destinations with the same scheme, host, port and protocol
     */
    public Object getTag()
    {
        return tag;
    }

    /**
     * @return the network protocol, or null if not specified
     */
    public Protocol getProtocol()
    {
        return protocol;
    }

    /**
     * @return the transport mechanism (TCP/IP, Unix domain socket, etc.)
     */
    public Transport getTransport()
    {
        return transport;
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(scheme, address, tag, protocol, transport);
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
               Objects.equals(protocol, that.protocol) &&
               Objects.equals(transport, that.transport);
    }

    /**
     * @return a string representation of this origin as a URI
     */
    public String asString()
    {
        return HttpURI.from(scheme, address.host, address.port, null).asString();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x[%s,tag=%s,protocol=%s,transport=%s]",
            getClass().getSimpleName(),
            hashCode(),
            asString(),
            getTag(),
            getProtocol(),
            getTransport()
        );
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

        /**
         * @return the host name or IP address
         */
        public String getHost()
        {
            return host;
        }

        /**
         * @return the port number
         */
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

        /**
         * @return a string representation of this address as host:port
         */
        public String asString()
        {
            return String.format("%s:%d", host, port);
        }

        /**
         * @return the socket address for this host and port
         */
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
            this.protocols = List.copyOf(protocols);
            this.negotiate = negotiate;
        }

        /**
         * @return the list of protocol names associated with this protocol
         */
        public List<String> getProtocols()
        {
            return protocols;
        }

        /**
         * @return true if this protocol should be negotiated, false otherwise
         */
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

        /**
         * @return a string representation of this protocol
         */
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
