//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.thread.Scheduler;

public class MockConnector extends ContainerLifeCycle implements Connector
{
    private final Server _server;

    public MockConnector(Server server)
    {
        _server = server;
    }

    @Override
    public Server getServer()
    {
        return _server;
    }

    @Override
    public Executor getExecutor()
    {
        return _server.getThreadPool();
    }

    @Override
    public Scheduler getScheduler()
    {
        return _server.getBean(Scheduler.class);
    }

    @Override
    public ByteBufferPool getByteBufferPool()
    {
        return _server.getBean(ByteBufferPool.class);
    }

    @Override
    public ConnectionFactory getConnectionFactory(String nextProtocol)
    {
        return null;
    }

    @Override
    public <T> T getConnectionFactory(Class<T> factoryType)
    {
        return null;
    }

    @Override
    public ConnectionFactory getDefaultConnectionFactory()
    {
        return null;
    }

    @Override
    public Collection<ConnectionFactory> getConnectionFactories()
    {
        return null;
    }

    @Override
    public List<String> getProtocols()
    {
        return null;
    }

    @Override
    public long getIdleTimeout()
    {
        return 0;
    }

    @Override
    public Object getTransport()
    {
        return null;
    }

    @Override
    public Collection<EndPoint> getConnectedEndPoints()
    {
        return null;
    }

    @Override
    public String getName()
    {
        return null;
    }

    @Override
    public CompletableFuture<Void> shutdown()
    {
        return null;
    }

    @Override
    public boolean isShutdown()
    {
        return false;
    }
}
