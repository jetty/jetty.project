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


package org.eclipse.jetty.maven.plugin;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.thread.Scheduler;




/**
 * MavenServerConnector
 *
 * As the ServerConnector class does not have a no-arg constructor, and moreover requires
 * the server instance passed in to all its constructors, it cannot
 * be referenced in the pom.xml. This class wraps a ServerConnector, delaying setting the
 * server instance. Only a few of the setters from the ServerConnector class are supported.
 */
public class MavenServerConnector extends AbstractLifeCycle implements Connector
{
    public static final int DEFAULT_PORT = 8080;
    public static final String DEFAULT_PORT_STR = String.valueOf(DEFAULT_PORT);
    public static final int DEFAULT_MAX_IDLE_TIME = 30000;
    
    private Server server;
    private ServerConnector delegate;
    private String host;
    private String name;
    private int port;
    private long idleTimeout;
    private int lingerTime;
    
    
    public MavenServerConnector()
    {
    }
    
    public void setServer(Server server)
    {
        this.server = server;
    }

    public void setHost(String host)
    {
        this.host = host;
    }
   
    public String getHost()
    {
        return this.host;
    }

    public void setPort(int port)
    {
        this.port = port;
    }
    
    public int getPort ()
    {
        return this.port;
    }

    public void setName (String name)
    {
        this.name = name;
    }
    
    public void setIdleTimeout(long idleTimeout)
    {
        this.idleTimeout = idleTimeout;
    }
    
    public void setSoLingerTime(int lingerTime)
    {
        this.lingerTime = lingerTime;
    }
    
    @Override
    protected void doStart() throws Exception
    {

        if (this.server == null)
            throw new IllegalStateException("Server not set for MavenServerConnector");

        this.delegate = new ServerConnector(this.server);
        this.delegate.setName(this.name);
        this.delegate.setPort(this.port);
        this.delegate.setHost(this.host);
        this.delegate.setIdleTimeout(idleTimeout);
        this.delegate.setSoLingerTime(lingerTime);
        this.delegate.start();

        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        this.delegate.stop();
        super.doStop();
        this.delegate = null;
    }

    /** 
     * @see org.eclipse.jetty.util.component.Graceful#shutdown()
     */
    @Override
    public Future<Void> shutdown()
    {
        checkDelegate();
        return this.delegate.shutdown();
    }

    /** 
     * @see org.eclipse.jetty.server.Connector#getServer()
     */
    @Override
    public Server getServer()
    {
        return this.server;
    }

    /** 
     * @see org.eclipse.jetty.server.Connector#getExecutor()
     */
    @Override
    public Executor getExecutor()
    {
        checkDelegate();
        return this.delegate.getExecutor();
    }

    /** 
     * @see org.eclipse.jetty.server.Connector#getScheduler()
     */
    @Override
    public Scheduler getScheduler()
    {
        checkDelegate();
        return this.delegate.getScheduler();
    }

    /** 
     * @see org.eclipse.jetty.server.Connector#getByteBufferPool()
     */
    @Override
    public ByteBufferPool getByteBufferPool()
    {
        checkDelegate();
        return this.delegate.getByteBufferPool();
    }

    /** 
     * @see org.eclipse.jetty.server.Connector#getConnectionFactory(java.lang.String)
     */
    @Override
    public ConnectionFactory getConnectionFactory(String nextProtocol)
    {
        checkDelegate();
        return this.delegate.getConnectionFactory(nextProtocol);
    }

    /** 
     * @see org.eclipse.jetty.server.Connector#getConnectionFactory(java.lang.Class)
     */
    @Override
    public <T> T getConnectionFactory(Class<T> factoryType)
    {
        checkDelegate();
        return this.delegate.getConnectionFactory(factoryType);
    }

    /** 
     * @see org.eclipse.jetty.server.Connector#getDefaultConnectionFactory()
     */
    @Override
    public ConnectionFactory getDefaultConnectionFactory()
    {
        checkDelegate();
        return this.delegate.getDefaultConnectionFactory();
    }

    /** 
     * @see org.eclipse.jetty.server.Connector#getConnectionFactories()
     */
    @Override
    public Collection<ConnectionFactory> getConnectionFactories()
    {
        checkDelegate();
        return this.delegate.getConnectionFactories();
    }

    /** 
     * @see org.eclipse.jetty.server.Connector#getProtocols()
     */
    @Override
    public List<String> getProtocols()
    {
        checkDelegate();
        return this.delegate.getProtocols();
    }

    /** 
     * @see org.eclipse.jetty.server.Connector#getIdleTimeout()
     */
    @Override
    @ManagedAttribute("maximum time a connection can be idle before being closed (in ms)")
    public long getIdleTimeout()
    {
        checkDelegate();
        return this.delegate.getIdleTimeout();
    }

    /** 
     * @see org.eclipse.jetty.server.Connector#getTransport()
     */
    @Override
    public Object getTransport()
    {
        checkDelegate();
        return this.delegate.getTransport();
    }

    /** 
     * @see org.eclipse.jetty.server.Connector#getConnectedEndPoints()
     */
    @Override
    public Collection<EndPoint> getConnectedEndPoints()
    {
        checkDelegate();
        return this.delegate.getConnectedEndPoints();
    }

    /** 
     * @see org.eclipse.jetty.server.Connector#getName()
     */
    @Override
    public String getName()
    {
        return this.name;
    }
    
    private void checkDelegate() throws IllegalStateException
    {
        if (this.delegate == null)
            throw new IllegalStateException ("MavenServerConnector delegate not ready");
    }
}
