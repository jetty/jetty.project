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

package org.eclipse.jetty.server;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.Graceful;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * <p>A {@link Connector} accept connections and data from remote peers,
 * and allows applications to send data to remote peers, by setting up
 * the machinery needed to handle such tasks.</p>
 */
@ManagedObject("Connector Interface")
public interface Connector extends LifeCycle, Graceful
{
    /**
     * @return the {@link Server} instance associated with this {@link Connector}
     */
    public Server getServer();

    /**
     * @return the {@link Executor} used to submit tasks
     */
    public Executor getExecutor();

    /**
     * @return the {@link Scheduler} used to schedule tasks
     */
    public Scheduler getScheduler();

    /**
     * @return the {@link ByteBufferPool} to acquire buffers from and release buffers to
     */
    public ByteBufferPool getByteBufferPool();

    /**
     * @param nextProtocol the next protocol
     * @return the {@link ConnectionFactory} associated with the protocol name
     */
    public ConnectionFactory getConnectionFactory(String nextProtocol);
    

    public <T> T getConnectionFactory(Class<T> factoryType);
    
    /**
     * @return the default {@link ConnectionFactory} associated with the default protocol name
     */
    public ConnectionFactory getDefaultConnectionFactory();
    
    public Collection<ConnectionFactory> getConnectionFactories();
    
    public List<String> getProtocols();
    
    /**
     * @return the max idle timeout for connections in milliseconds
     */
    @ManagedAttribute("maximum time a connection can be idle before being closed (in ms)")
    public long getIdleTimeout();

    /**
     * @return the underlying socket, channel, buffer etc. for the connector.
     */
    public Object getTransport();
    
    /**
     * @return immutable collection of connected endpoints
     */
    public Collection<EndPoint> getConnectedEndPoints();

    
    /* ------------------------------------------------------------ */
    /**
     * Get the connector name if set.
     * <p>A {@link ContextHandler} may be configured with
     * virtual hosts in the form "@connectorName" and will only serve
     * requests from the named connector.
     * @return The connector name or null.
     */
    public String getName();
}
