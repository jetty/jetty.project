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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jetty.util.thread.Scheduler;

/**
 * <p>The low-level transport protocol used by clients.</p>
 * <p>A high-level protocol such as HTTP/1.1 can be transported over a low-level
 * protocol such as TCP/IP, Unix-Domain sockets, QUIC, shared memory, etc.</p>
 * <p>This class defines the programming interface to implement low-level
 * protocols, and useful implementations for commonly used low-level
 * protocols such as TCP/IP or Unix-Domain sockets.</p>
 * <p>Low-level transports may be layered; some of them maybe considered
 * lower-level than others, but from the point of view of the high-level
 * protocols they are all considered low-level.</p>
 * <p>For example, QUIC is typically layered on top of the UDP/IP low-level
 * transport protocol, but it may be layered on top Unix-Domain sockets,
 * or on top of shared memory.
 * As QUIC provides a reliable, ordered, stream-based transport, it may
 * be seen as a replacement for TCP, and high-level protocols that need
 * a reliable, ordered, stream-based transport may use either the non-layered
 * TCP/IP or the layered QUIC over UDP/IP without noticing the difference.
 * This makes possible to transport HTTP/1.1 over QUIC over Unix-Domain
 * sockets, or HTTP/2 over QUIC over shared memory, etc.</p>
 */
public interface TransportProtocol
{
    /**
     * <p>The transport protocol TCP/IP.</p>
     */
    TransportProtocol TCP_IP = new TCPIP();

    /**
     * <p>The transport protocol UDP/IP.</p>
     */
    TransportProtocol UDP_IP = new UDPIP();

    /**
     * @return whether this transport protocol is intrinsically secure.
     */
    default boolean isIntrinsicallySecure()
    {
        return false;
    }

    /**
     * <p>Returns whether this transport protocol requires resolution of domain
     * names.</p>
     * <p>When domain name resolution is required, it must be performed by
     * an external service, and the value returned by {@link #getSocketAddress()}
     * is ignored, while the resolved socket address is eventually passed to
     * {@link #connect(SocketAddress, Map)}.
     * Otherwise, domain name resolution is not required, and the value returned
     * by {@link #getSocketAddress()} is eventually passed to
     * {@link #connect(SocketAddress, Map)}.</p>
     *
     * @return whether this transport protocol requires domain names resolution
     */
    default boolean requiresDomainNamesResolution()
    {
        return false;
    }

    /**
     * <p>Establishes a connection to the given socket address.</p>
     *
     * @param socketAddress the socket address to connect to
     * @param context the context information to establish the connection
     */
    default void connect(SocketAddress socketAddress, Map<String, Object> context)
    {
    }

    /**
     * @return the socket address to use in case domain name resolution is not required
     */
    default SocketAddress getSocketAddress()
    {
        return null;
    }

    /**
     * <p>For transport protocols that are based on sockets, or for transport protocols
     * that are layered on top of another transport protocol that is based on sockets,
     * this method is invoked to create a new {@link SelectableChannel} used for the
     * socket communication.</p>
     *
     * @return a new {@link SelectableChannel} used for the socket communication,
     * or {@code null} if the communication does not use sockets.
     * @throws IOException if the {@link SelectableChannel} cannot be created
     */
    default SelectableChannel newSelectableChannel() throws IOException
    {
        return null;
    }

    /**
     * <p>For transport protocols that are based on sockets, or for transport protocols
     * that are layered on top of another transport protocol that is based on sockets,
     * this method is invoked to create a new {@link EndPoint} that wraps the
     * {@link SelectableChannel} created by {@link #newSelectableChannel()}.</p>
     *
     * @param scheduler the {@link Scheduler}
     * @param selector the {@link ManagedSelector}
     * @param selectable the {@link SelectableChannel}
     * @param selectionKey the {@link SelectionKey}
     * @return a new {@link EndPoint}
     */
    default EndPoint newEndPoint(Scheduler scheduler, ManagedSelector selector, SelectableChannel selectable, SelectionKey selectionKey)
    {
        return null;
    }

    /**
     * <p>Creates a new {@link Connection} to be associated with the given low-level {@link EndPoint}.</p>
     * <p>For non-layered transport protocols such as TCP/IP, the {@link Connection} is typically
     * that of the high-level protocol.
     * For layered transport protocols such as QUIC, the {@link Connection} is typically that of the
     * layered transport protocol.</p>
     *
     * @param endPoint the {@link EndPoint} to associate the {@link Connection} to
     * @param context the context information to create the connection
     * @return a new {@link Connection}
     * @throws IOException if the {@link Connection} cannot be created
     */
    default Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
    {
        ClientConnectionFactory factory = (ClientConnectionFactory)context.get(ClientConnector.CLIENT_CONNECTION_FACTORY_CONTEXT_KEY);
        return factory.newConnection(endPoint, context);
    }

    int hashCode();

    boolean equals(Object obj);

    /**
     * <p>Abstract implementation of transport protocols based on sockets.</p>
     */
    abstract class Socket implements TransportProtocol
    {
        @Override
        public void connect(SocketAddress socketAddress, Map<String, Object> context)
        {
            ClientConnector connector = (ClientConnector)context.get(ClientConnector.CLIENT_CONNECTOR_CONTEXT_KEY);
            connector.connect(socketAddress, context);
        }

        @Override
        public String toString()
        {
            return "%s@%x".formatted(getClass().getSimpleName(), hashCode());
        }
    }

    /**
     * <p>Abstract implementation of transport protocols based on IP.</p>
     */
    abstract class IP extends Socket
    {
        @Override
        public boolean requiresDomainNamesResolution()
        {
            return true;
        }
    }

    /**
     * <p>The TCP/IP transport protocol.</p>
     */
    class TCPIP extends IP
    {
        protected TCPIP()
        {
            // Do not instantiate, use the singleton.
        }

        @Override
        public SelectableChannel newSelectableChannel() throws IOException
        {
            return SocketChannel.open();
        }

        @Override
        public EndPoint newEndPoint(Scheduler scheduler, ManagedSelector selector, SelectableChannel selectable, SelectionKey selectionKey)
        {
            return new SocketChannelEndPoint((SocketChannel)selectable, selector, selectionKey, scheduler);
        }
    }

    /**
     * <p>The UDP/IP transport protocol.</p>
     */
    class UDPIP extends TransportProtocol.IP
    {
        protected UDPIP()
        {
            // Do not instantiate, use the singleton.
        }

        @Override
        public SelectableChannel newSelectableChannel() throws IOException
        {
            return DatagramChannel.open();
        }

        @Override
        public EndPoint newEndPoint(Scheduler scheduler, ManagedSelector selector, SelectableChannel selectable, SelectionKey selectionKey)
        {
            return new DatagramChannelEndPoint((DatagramChannel)selectable, selector, selectionKey, scheduler);
        }
    }

    /**
     * <p>Abstract implementation of transport protocols based on Unix-Domain sockets.</p>
     */
    abstract class Unix extends Socket
    {
        private final UnixDomainSocketAddress socketAddress;

        protected Unix(Path path)
        {
            this.socketAddress = UnixDomainSocketAddress.of(path);
        }

        @Override
        public SocketAddress getSocketAddress()
        {
            return socketAddress;
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(socketAddress);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj instanceof Unix unix)
                return Objects.equals(socketAddress, unix.socketAddress);
            return false;
        }

        @Override
        public String toString()
        {
            return "%s[%s]".formatted(super.toString(), socketAddress.getPath());
        }
    }

    /**
     * <p>The stream Unix-Domain socket transport protocol.</p>
     */
    class TCPUnix extends Unix
    {
        public TCPUnix(Path path)
        {
            super(path);
        }

        @Override
        public SelectableChannel newSelectableChannel() throws IOException
        {
            return SocketChannel.open(StandardProtocolFamily.UNIX);
        }

        @Override
        public EndPoint newEndPoint(Scheduler scheduler, ManagedSelector selector, SelectableChannel selectable, SelectionKey selectionKey)
        {
            return new SocketChannelEndPoint((SocketChannel)selectable, selector, selectionKey, scheduler);
        }
    }

    /**
     * <p>The datagram Unix-Domain socket transport protocol.</p>
     */
    class UDPUnix extends Unix
    {
        public UDPUnix(Path path)
        {
            super(path);
        }

        @Override
        public SelectableChannel newSelectableChannel() throws IOException
        {
            return DatagramChannel.open(StandardProtocolFamily.UNIX);
        }

        @Override
        public EndPoint newEndPoint(Scheduler scheduler, ManagedSelector selector, SelectableChannel selectable, SelectionKey selectionKey)
        {
            return new DatagramChannelEndPoint((DatagramChannel)selectable, selector, selectionKey, scheduler);
        }
    }

    /**
     * <p>A wrapper for {@link TransportProtocol} instances to allow layering of transport protocols.</p>
     */
    class Wrapper implements TransportProtocol
    {
        private final TransportProtocol wrapped;

        public Wrapper(TransportProtocol wrapped)
        {
            this.wrapped = Objects.requireNonNull(wrapped);
        }

        public TransportProtocol getWrapped()
        {
            return wrapped;
        }

        public TransportProtocol unwrap()
        {
            TransportProtocol result = getWrapped();
            while (true)
            {
                if (result instanceof Wrapper wrapper)
                    result = wrapper.getWrapped();
                else
                    break;
            }
            return result;
        }

        @Override
        public boolean isIntrinsicallySecure()
        {
            return wrapped.isIntrinsicallySecure();
        }

        @Override
        public boolean requiresDomainNamesResolution()
        {
            return wrapped.requiresDomainNamesResolution();
        }

        @Override
        public void connect(SocketAddress socketAddress, Map<String, Object> context)
        {
            wrapped.connect(socketAddress, context);
        }

        @Override
        public SocketAddress getSocketAddress()
        {
            return wrapped.getSocketAddress();
        }

        @Override
        public SelectableChannel newSelectableChannel() throws IOException
        {
            return wrapped.newSelectableChannel();
        }

        @Override
        public EndPoint newEndPoint(Scheduler scheduler, ManagedSelector selector, SelectableChannel selectable, SelectionKey selectionKey)
        {
            return wrapped.newEndPoint(scheduler, selector, selectable, selectionKey);
        }

        @Override
        public Connection newConnection(EndPoint endPoint, Map<String, Object> context) throws IOException
        {
            return wrapped.newConnection(endPoint, context);
        }

        @Override
        public String toString()
        {
            return "%s@%x[%s]".formatted(getClass().getSimpleName(), hashCode(), getWrapped());
        }
    }
}
