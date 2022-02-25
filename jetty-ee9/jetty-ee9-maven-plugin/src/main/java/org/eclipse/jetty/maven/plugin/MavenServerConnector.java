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

package org.eclipse.jetty.maven.plugin;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * MavenServerConnector
 *
 * As the ServerConnector class does not have a no-arg constructor, and moreover requires
 * the server instance passed in to all its constructors, it cannot
 * be referenced in the pom.xml. This class wraps a ServerConnector, delaying setting the
 * server instance. Only a few of the setters from the ServerConnector class are supported.
 */
public class MavenServerConnector extends ContainerLifeCycle implements Connector
{
    public static String PORT_SYSPROPERTY = "jetty.http.port";

    public static final int DEFAULT_PORT = 8080;
    public static final String DEFAULT_PORT_STR = String.valueOf(DEFAULT_PORT);
    public static final int DEFAULT_MAX_IDLE_TIME = 30000;

    private Server server;
    private volatile ServerConnector delegate;
    private String host;
    private String name;
    private int port;
    private long idleTimeout;

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

    public int getPort()
    {
        return this.port;
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public void setIdleTimeout(long idleTimeout)
    {
        this.idleTimeout = idleTimeout;
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

    @Override
    public CompletableFuture<Void> shutdown()
    {
        return checkDelegate().shutdown();
    }

    @Override
    public boolean isShutdown()
    {
        return checkDelegate().isShutdown();
    }

    @Override
    public Server getServer()
    {
        return this.server;
    }

    @Override
    public Executor getExecutor()
    {
        return checkDelegate().getExecutor();
    }

    @Override
    public Scheduler getScheduler()
    {
        return checkDelegate().getScheduler();
    }

    @Override
    public ByteBufferPool getByteBufferPool()
    {
        return checkDelegate().getByteBufferPool();
    }

    @Override
    public ConnectionFactory getConnectionFactory(String nextProtocol)
    {
        return checkDelegate().getConnectionFactory(nextProtocol);
    }

    @Override
    public <T> T getConnectionFactory(Class<T> factoryType)
    {
        return checkDelegate().getConnectionFactory(factoryType);
    }

    @Override
    public ConnectionFactory getDefaultConnectionFactory()
    {
        return checkDelegate().getDefaultConnectionFactory();
    }

    @Override
    public Collection<ConnectionFactory> getConnectionFactories()
    {
        return checkDelegate().getConnectionFactories();
    }

    @Override
    public List<String> getProtocols()
    {
        return checkDelegate().getProtocols();
    }

    @Override
    @ManagedAttribute("maximum time a connection can be idle before being closed (in ms)")
    public long getIdleTimeout()
    {
        return checkDelegate().getIdleTimeout();
    }

    @Override
    public Object getTransport()
    {
        return checkDelegate().getTransport();
    }

    @Override
    public Collection<EndPoint> getConnectedEndPoints()
    {
        return checkDelegate().getConnectedEndPoints();
    }

    @Override
    public String getName()
    {
        return this.name;
    }

    public int getLocalPort()
    {
        return this.delegate.getLocalPort();
    }

    private ServerConnector checkDelegate() throws IllegalStateException
    {
        ServerConnector d = this.delegate;
        if (d == null)
            throw new IllegalStateException("MavenServerConnector delegate not ready");
        return d;
    }
}
