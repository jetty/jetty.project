//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.io.IOException;

import org.eclipse.jetty.io.Buffers;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.ThreadPool;

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
 * 
 * 
 */
/**
 * @author gregw
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
    void setServer(Server server);
    
    /* ------------------------------------------------------------ */
    Server getServer();

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the request header buffer size in bytes.
     */
    int getRequestHeaderSize();
    
    /* ------------------------------------------------------------ */
    /**
     * Set the size of the buffer to be used for request headers.
     * @param size The size in bytes.
     */
    void setRequestHeaderSize(int size);

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the response header buffer size in bytes.
     */
    int getResponseHeaderSize();
    
    /* ------------------------------------------------------------ */
    /**
     * Set the size of the buffer to be used for request headers.
     * @param size The size in bytes.
     */
    void setResponseHeaderSize(int size);
    

    /* ------------------------------------------------------------ */
    /**
     * @return factory for request buffers
     */
    Buffers getRequestBuffers();

    /* ------------------------------------------------------------ */
    /**
     * @return factory for response buffers
     */
    Buffers getResponseBuffers();
    
    
    /* ------------------------------------------------------------ */
    /**
     * @return Returns the requestBufferSize.
     */
    int getRequestBufferSize();
    
    /* ------------------------------------------------------------ */
    /**
     * Set the size of the content buffer for receiving requests. 
     * These buffers are only used for active connections that have
     * requests with bodies that will not fit within the header buffer.
     * @param requestBufferSize The requestBufferSize to set.
     */
    void setRequestBufferSize(int requestBufferSize);
    
    /* ------------------------------------------------------------ */
    /**
     * @return Returns the responseBufferSize.
     */
    int getResponseBufferSize();
    
    /* ------------------------------------------------------------ */
    /**
     * Set the size of the content buffer for sending responses. 
     * These buffers are only used for active connections that are sending 
     * responses with bodies that will not fit within the header buffer.
     * @param responseBufferSize The responseBufferSize to set.
     */
    void setResponseBufferSize(int responseBufferSize);
    

    /* ------------------------------------------------------------ */
    /**
     * @return The port to use when redirecting a request if a data constraint of integral is 
     * required. See {@link org.eclipse.jetty.util.security.Constraint#getDataConstraint()}
     */
    int getIntegralPort();

    /* ------------------------------------------------------------ */
    /**
     * @return The schema to use when redirecting a request if a data constraint of integral is 
     * required. See {@link org.eclipse.jetty.util.security.Constraint#getDataConstraint()}
     */
    String getIntegralScheme();

    /* ------------------------------------------------------------ */
    /**
     * @param request A request
     * @return true if the request is integral. This normally means the https schema has been used.
     */
    boolean isIntegral(Request request);

    /* ------------------------------------------------------------ */
    /**
     * @return The port to use when redirecting a request if a data constraint of confidential is 
     * required. See {@link org.eclipse.jetty.util.security.Constraint#getDataConstraint()}
     */
    int getConfidentialPort();
    

    /* ------------------------------------------------------------ */
    /**
     * @return The schema to use when redirecting a request if a data constraint of confidential is 
     * required. See {@link org.eclipse.jetty.util.security.Constraint#getDataConstraint()}
     */
    String getConfidentialScheme();
    
    /* ------------------------------------------------------------ */
    /**
     * @param request A request
     * @return true if the request is confidential. This normally means the https schema has been used.
     */
    boolean isConfidential(Request request);

    /* ------------------------------------------------------------ */
    /** Customize a request for an endpoint.
     * Called on every request to allow customization of the request for
     * the particular endpoint (eg security properties from a SSL connection).
     * @param endpoint
     * @param request
     * @throws IOException
     */
    void customize(EndPoint endpoint, Request request) throws IOException;

    /* ------------------------------------------------------------ */
    /** Persist an endpoint.
     * Called after every request if the connection is to remain open.
     * @param endpoint
     * @throws IOException
     */
    void persist(EndPoint endpoint) throws IOException;
    
    /* ------------------------------------------------------------ */
    /**
     * @return The hostname representing the interface to which 
     * this connector will bind, or null for all interfaces.
     */
    String getHost();
    
    /* ------------------------------------------------------------ */
    /**
     * Set the hostname of the interface to bind to.
     * @param hostname The hostname representing the interface to which 
     * this connector will bind, or null for all interfaces.
     */
    void setHost(String hostname);

    /* ------------------------------------------------------------ */
    /**
     * @param port The port to listen of for connections or 0 if any available
     * port may be used.
     */
    void setPort(int port);
    
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
    
    /**
     * @param ms Max Idle time for connections in milliseconds
     */
    void setMaxIdleTime(int ms);
    
    /* ------------------------------------------------------------ */
    int getLowResourceMaxIdleTime();
    void setLowResourceMaxIdleTime(int ms);
    
    /* ------------------------------------------------------------ */
    /**
     * @return the underlying socket, channel, buffer etc. for the connector.
     */
    Object getConnection();
    
    
    /* ------------------------------------------------------------ */
    /**
     * @return true if names resolution should be done.
     */
    boolean getResolveNames();
    
    

    /* ------------------------------------------------------------ */
    /**
     * @return Get the number of requests handled by this connector
     * since last call of statsReset(). If setStatsOn(false) then this
     * is undefined.
     */
    public int getRequests();

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
     * @return Mean number of requests per connection
     * since statsReset() called. Undefined if setStatsOn(false).
     */
    public double getConnectionsRequestsMean() ;

    /* ------------------------------------------------------------ */
    /** 
     * @return Standard Deviation of number of requests per connection
     * since statsReset() called. Undefined if setStatsOn(false).
     */
    public double getConnectionsRequestsStdDev() ;

    /* ------------------------------------------------------------ */
    /** 
     * @return Maximum number of requests per connection
     * since statsReset() called. Undefined if setStatsOn(false).
     */
    public int getConnectionsRequestsMax();

    /* ------------------------------------------------------------ */
    /** Reset statistics.
     */
    public void statsReset();
    
    /* ------------------------------------------------------------ */
    public void setStatsOn(boolean on);
    
    /* ------------------------------------------------------------ */
    /** 
     * @return True if statistics collection is turned on.
     */
    public boolean getStatsOn();
    
    /* ------------------------------------------------------------ */
    /** 
     * @return Timestamp stats were started at.
     */
    public long getStatsOnMs();
    

    /* ------------------------------------------------------------ */
    /** Check if low on resources.
     * For most connectors, low resources is measured by calling 
     * {@link ThreadPool#isLowOnThreads()} on the connector threadpool
     * or the server threadpool if there is no connector threadpool.
     * <p>
     * For blocking connectors, low resources is used to trigger
     * usage of {@link #getLowResourceMaxIdleTime()} for the timeout
     * of an idle connection.
     * <p>
     * for non-blocking connectors, the number of connections is
     * used instead of this method, to select the timeout of an 
     * idle connection.
     * <p>
     * For all connectors, low resources is used to trigger the 
     * usage of {@link #getLowResourceMaxIdleTime()} for read and 
     * write operations.
     * 
     * @return true if this connector is low on resources.
     */
    public boolean isLowResources();
}
