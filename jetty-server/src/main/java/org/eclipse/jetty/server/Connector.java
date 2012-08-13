// ========================================================================
// Copyright (c) 2004-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================

package org.eclipse.jetty.server;

import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * <p>A {@link Connector} accept connections and data from remote peers,
 * and allows applications to send data to remote peers, by setting up
 * the machinery needed to handle such tasks.</p>
 */
@ManagedObject("Connector Interface")
public interface Connector extends LifeCycle
{
    /**
     * @return the name of the connector, defaulting to host:port
     */
    public String getName();

    /**
     * @return the {@link Server} instance associated with this {@link Connector}
     */
    public Server getServer();

    /**
     * @return the {@link Executor} used to submit tasks
     */
    public Executor getExecutor();

    /**
     * @return the {@link ScheduledExecutorService} used to schedule tasks
     */
    public ScheduledExecutorService getScheduler();

    /**
     * @return the {@link ByteBufferPool} to acquire buffers from and release buffers to
     */
    public ByteBufferPool getByteBufferPool();

    /**
     * @return the {@link SslContextFactory} associated with this {@link Connector}
     */
    public SslContextFactory getSslContextFactory();

    /**
     * @return the dle timeout for connections in milliseconds
     */
    @ManagedAttribute("maximum time a connection can be idle before being closed (in ms)")
    public long getIdleTimeout();

    /**
     * @return the underlying socket, channel, buffer etc. for the connector.
     */
    public Object getTransport();

    /**
     * @return the {@link Statistics} associated with this {@link Connector}
     */
    public Statistics getStatistics();

    /**
     * <p>{@link Connector} statistics.</p>
     */
    public interface Statistics extends LifeCycle
    {
        /**
         * <p>Resets the statistics.</p>
         */
        public void reset();

        /**
         * @return the number of messages received by this connector
         * since last call to {@link #reset}.
         */
        public int getMessagesIn();

        /**
         * @return the number of messages sent by this connector
         * since last call to {@link #reset}.
         */
        public int getMessagesOut();

        /**
         * @return the number of bytes received by this connector
         * since last call to {@link #reset}.
         */
        public int getBytesIn();

        /**
         * @return the number of bytes sent by this connector
         * since last call to {@link #reset}.
         */
        public int getBytesOut();

        /**
         * @return the total time connections have been open, in milliseconds,
         * since last call to {@link #reset}.
         */
        public long getConnectionsDurationTotal();

        /**
         * @return the number of connections accepted by the server
         * since last call to {@link #reset}.
         */
        public int getConnections() ;

        /**
         * @return the number of connections currently open that were opened
         * since last call to {@link #reset}.
         */
        public int getConnectionsOpen() ;

        /**
         * @return the max number of connections opened simultaneously
         * since last call to {@link #reset}.
         */
        public int getConnectionsOpenMax() ;

        /**
         * @return the max time a connection has been open, in milliseconds,
         * since last call to {@link #reset}.
         */
        public long getConnectionsDurationMax();

        /**
         * @return the mean time connections have been open, in milliseconds,
         * since last call to {@link #reset}.
         */
        public double getConnectionsDurationMean() ;

        /**
         * @return the standard deviation of the time connections have been open, in milliseconds,
         * since last call to {@link #reset}.
         */
        public double getConnectionsDurationStdDev() ;

        /**
         * @return the mean number of messages received per connection
         * since last call to {@link #reset}.
         */
        public double getConnectionsMessagesInMean() ;

        /**
         * @return the standard deviation of the number of messages received per connection
         * since last call to {@link #reset}.
         */
        public double getConnectionsMessagesInStdDev() ;

        /**
         * @return the max number of messages received by a connection
         * since last call to {@link #reset}.
         */
        public int getConnectionsMessagesInMax();

        /**
         * @return the number of milliseconds the statistics have been started or reset
         */
        public long getStartedMillis();

        /**
         * <p>Callback method invoked when a new connection is opened.</p>
         */
        public void connectionOpened();

        /**
         * <p>Callback method invoked when a connection is upgraded.</p>
         *
         * @param duration the time the previous connection was opened
         * @param messagesIn the number of messages received by the previous connection
         * @param messagesOut the number of messages send by the previous connection
         */
        public void connectionUpgraded(long duration, int messagesIn, int messagesOut);

        /**
         * <p>Callback method invoked when a connection is closed.</p>
         *
         * @param duration the time the connection was opened
         * @param messagesIn the number of messages received by the connection
         * @param messagesOut the number of messages send by the connection
         */
        public void connectionClosed(long duration, int messagesIn, int messagesOut);
    }
}
