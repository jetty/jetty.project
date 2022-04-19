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

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.NullByteBufferPool;
import org.eclipse.jetty.server.AbstractConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.Scheduler;

public class MockConnector extends AbstractConnector
{
    private static final ByteBufferPool BUFFER_POOL = new NullByteBufferPool();
    private final Server _server;

    public MockConnector()
    {
        this(new Server());
    }

    public MockConnector(Server server)
    {
        super(server, server.getThreadPool(), server.getBean(Scheduler.class), BUFFER_POOL, 0);
        _server = server;
    }

    @Override
    protected void accept(int acceptorID) throws IOException, InterruptedException
    {
    }

    @Override
    public Scheduler getScheduler()
    {
        return _server.getBean(Scheduler.class);
    }

    @Override
    public ByteBufferPool getByteBufferPool()
    {
        ByteBufferPool pool = _server.getBean(ByteBufferPool.class);
        if (pool != null)
            return pool;
        return super.getByteBufferPool();
    }

    @Override
    public Object getTransport()
    {
        return null;
    }
}

