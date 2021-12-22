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

package org.eclipse.jetty.server;

import java.net.SocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.HostPort;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.Graceful;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * <p>A {@link Connector} accept connections and data from remote peers,
 * and allows applications to send data to remote peers, by setting up
 * the machinery needed to handle such tasks.</p>
 */
@ManagedObject("Connector Interface")
public interface Connector extends LifeCycle, Container, Graceful
{
    /**
     * @return the {@link Server} instance associated with this {@link Connector}
     */
    Server getServer();

    /**
     * @return the {@link Executor} used to submit tasks
     */
    Executor getExecutor();

    /**
     * @return the {@link Scheduler} used to schedule tasks
     */
    Scheduler getScheduler();

    /**
     * @return the {@link ByteBufferPool} to acquire buffers from and release buffers to
     */
    ByteBufferPool getByteBufferPool();

    /**
     * @param nextProtocol the next protocol
     * @return the {@link ConnectionFactory} associated with the protocol name
     */
    ConnectionFactory getConnectionFactory(String nextProtocol);

    <T> T getConnectionFactory(Class<T> factoryType);

    /**
     * @return the default {@link ConnectionFactory} associated with the default protocol name
     */
    ConnectionFactory getDefaultConnectionFactory();

    Collection<ConnectionFactory> getConnectionFactories();

    List<String> getProtocols();

    /**
     * @return the max idle timeout for connections in milliseconds
     */
    @ManagedAttribute("maximum time a connection can be idle before being closed (in ms)")
    long getIdleTimeout();

    /**
     * @return the underlying socket, channel, buffer etc. for the connector.
     */
    Object getTransport();

    /**
     * @return immutable collection of connected endpoints
     */
    Collection<EndPoint> getConnectedEndPoints();

    /**
     * Get the connector name if set.
     * <p>A {@link ContextHandler} may be configured with
     * virtual hosts in the form "@connectorName" and will only serve
     * requests from the named connector.
     *
     * @return The connector name or null.
     */
    String getName();

    /**
     * Get the Local Address of the connection
     *
     * @return Returns the connection local address.
     */
    SocketAddress getLocalAddress();

    /**
     * <p>
     * Specify the connection local address used within application API layer
     * when identifying the local host name/port of a connected endpoint.
     * </p>
     * <p>
     * This allows an override of higher level APIs, such as
     * {@code ServletRequest.getLocalName()}, {@code ServletRequest.getLocalAddr()},
     * and {@code ServletRequest.getLocalPort()}.
     * </p>
     *
     * @param localAddress the address to use for host/addr/port, or null to reset to default behavior
     */
    void setLocalAddress(SocketAddress localAddress);

    /**
     * Get the optional Server URI authority default
     *
     * @return Returns the connection server authority (name/port).
     */
    HostPort getServerAuthority();

    /**
     * <p>
     * Specify the connection server uri authority (name/port) used within application API layer
     * when identifying the server host name/port of a connected endpoint.
     * </p>
     *
     * <p>
     * This allows an override of higher level APIs, such as
     * {@code ServletRequest.getServerName()}, and {@code ServletRequest.getServerPort()}.
     * </p>
     *
     * @param authority the authority host (and optional port), or null to reset to default behavior
     */
    void setServerAuthority(HostPort authority);
}
