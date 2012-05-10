// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.component.LifeCycle;

/** HTTP Connector.
 * Implementations of this interface provide connectors for the HTTP protocol.
 * A connector receives requests (normally from a socket) and calls the 
 * handle method of the Handler object.  These operations are performed using
 * threads from the ThreadPool set on the connector.
 * 
 * When a connector is registered with an instance of Server, then the server
 * will set itself as both the ThreadPool and the Handler.  Note that a connector
 * can be used without a Server if a thread pool and handler are directly provided.
 * 
 */
public interface Connector extends LifeCycle
{ 
    /* ------------------------------------------------------------ */
    /**
     * @return the name of the connector. Defaults to the HostName:port
     */
    String getName();
    
    /* ------------------------------------------------------------ */
    /**
     * Opens the connector 
     * @throws IOException
     */
    void open() throws IOException;

    /* ------------------------------------------------------------ */
    void close() throws IOException;

    /* ------------------------------------------------------------ */
    Server getServer();

    /* ------------------------------------------------------------ */
    Executor getExecutor();    
    
    /* ------------------------------------------------------------ */
    ByteBufferPool getByteBufferPool();    
    
    /* ------------------------------------------------------------ */
    /**
     * @return The hostname representing the interface to which 
     * this connector will bind, or null for all interfaces.
     */
    String getHost();
    
    /* ------------------------------------------------------------ */
    /**
     * @return The configured port for the connector or 0 if any available
     * port may be used.
     */
    int getPort();
    
    /* ------------------------------------------------------------ */
    /**
     * @return The actual port the connector is listening on or
     * -1 if it has not been opened, or -2 if it has been closed.
     */
    int getLocalPort();
    
    /* ------------------------------------------------------------ */
    /**
     * @return Max Idle time for connections in milliseconds
     */
    int getMaxIdleTime();
    
    
    /* ------------------------------------------------------------ */
    /**
     * @return the underlying socket, channel, buffer etc. for the connector.
     */
    Object getConnection();

    
    /* ------------------------------------------------------------ */
    Statistics getStatistics();

    
    interface Statistics extends LifeCycle
    {
        /* ------------------------------------------------------------ */
        /** 
         * @return True if statistics collection is turned on.
         */
        boolean getStatsOn();
        
        /* ------------------------------------------------------------ */
        /** Reset statistics.
         */
        void statsReset();
        
        /* ------------------------------------------------------------ */
        /**
         * @return Get the number of messages received by this connector
         * since last call of statsReset(). If setStatsOn(false) then this
         * is undefined.
         */
        public int getMessagesIn();
        
        /* ------------------------------------------------------------ */
        /**
         * @return Get the number of messages sent by this connector
         * since last call of statsReset(). If setStatsOn(false) then this
         * is undefined.
         */
        public int getMessagesOut();
        
        /* ------------------------------------------------------------ */
        /**
         * @return Get the number of bytes received by this connector
         * since last call of statsReset(). If setStatsOn(false) then this
         * is undefined.
         */
        public int getBytesIn();
        
        /* ------------------------------------------------------------ */
        /**
         * @return Get the number of bytes sent by this connector
         * since last call of statsReset(). If setStatsOn(false) then this
         * is undefined.
         */
        public int getBytesOut();

        /* ------------------------------------------------------------ */
        /**
         * @return Returns the connectionsDurationTotal.
         */
        public long getConnectionsDurationTotal();

        /* ------------------------------------------------------------ */
        /** 
         * @return Number of connections accepted by the server since
         * statsReset() called. Undefined if setStatsOn(false).
         */
        public int getConnections() ;

        /* ------------------------------------------------------------ */
        /** 
         * @return Number of connections currently open that were opened
         * since statsReset() called. Undefined if setStatsOn(false).
         */
        public int getConnectionsOpen() ;

        /* ------------------------------------------------------------ */
        /** 
         * @return Maximum number of connections opened simultaneously
         * since statsReset() called. Undefined if setStatsOn(false).
         */
        public int getConnectionsOpenMax() ;

        /* ------------------------------------------------------------ */
        /** 
         * @return Maximum duration in milliseconds of an open connection
         * since statsReset() called. Undefined if setStatsOn(false).
         */
        public long getConnectionsDurationMax();

        /* ------------------------------------------------------------ */
        /** 
         * @return Mean duration in milliseconds of open connections
         * since statsReset() called. Undefined if setStatsOn(false).
         */
        public double getConnectionsDurationMean() ;

        /* ------------------------------------------------------------ */
        /** 
         * @return Standard deviation of duration in milliseconds of
         * open connections since statsReset() called. Undefined if
         * setStatsOn(false).
         */
        public double getConnectionsDurationStdDev() ;

        /* ------------------------------------------------------------ */
        /** 
         * @return Mean number of messages received per connection
         * since statsReset() called. Undefined if setStatsOn(false).
         */
        public double getConnectionsMessagesInMean() ;

        /* ------------------------------------------------------------ */
        /** 
         * @return Standard Deviation of number of messages received per connection
         * since statsReset() called. Undefined if setStatsOn(false).
         */
        public double getConnectionsMessagesInStdDev() ;

        /* ------------------------------------------------------------ */
        /** 
         * @return Maximum number of messages received per connection
         * since statsReset() called. Undefined if setStatsOn(false).
         */
        public int getConnectionsMessagesInMax();
        
        /* ------------------------------------------------------------ */
        /** 
         * @return Timestamp stats were started at.
         */
        public long getStatsOnMs();

        void connectionOpened();

        void connectionUpgraded(long duration, int requests, int requests2);

        void connectionClosed(long duration, int requests, int requests2);

    }
}
