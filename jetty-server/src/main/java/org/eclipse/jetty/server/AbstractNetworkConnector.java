//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * An abstract Network Connector.
 * <p>
 * Extends the {@link AbstractConnector} support for the {@link NetworkConnector} interface.
 */
@ManagedObject("AbstractNetworkConnector")
public abstract class AbstractNetworkConnector extends AbstractConnector implements NetworkConnector
{

    private volatile String _host;
    private volatile int _port = 0;

    public AbstractNetworkConnector(Server server, Executor executor, Scheduler scheduler, ByteBufferPool pool, int acceptors, ConnectionFactory... factories)
    {
        super(server, executor, scheduler, pool, acceptors, factories);
    }

    public void setHost(String host)
    {
        _host = host;
    }

    @Override
    @ManagedAttribute("The network interface this connector binds to as an IP address or a hostname.  If null or 0.0.0.0, then bind to all interfaces.")
    public String getHost()
    {
        return _host;
    }

    public void setPort(int port)
    {
        _port = port;
    }

    @Override
    @ManagedAttribute("Port this connector listens on. If set the 0 a random port is assigned which may be obtained with getLocalPort()")
    public int getPort()
    {
        return _port;
    }

    @Override
    public int getLocalPort()
    {
        return -1;
    }

    @Override
    protected void doStart() throws Exception
    {
        open();
        super.doStart();
    }

    @Override
    protected void doStop() throws Exception
    {
        close();
        super.doStop();
    }

    @Override
    public void open() throws IOException
    {
    }

    @Override
    public void close()
    {
    }

    @Override
    public Future<Void> shutdown()
    {
        close();
        return super.shutdown();
    }

    @Override
    protected boolean handleAcceptFailure(Throwable ex)
    {
        if (isOpen())
            return super.handleAcceptFailure(ex);
        LOG.ignore(ex);
        return false;
    }

    @Override
    public String toString()
    {
        return String.format("%s{%s:%d}",
            super.toString(),
            getHost() == null ? "0.0.0.0" : getHost(),
            getLocalPort() <= 0 ? getPort() : getLocalPort());
    }
}
