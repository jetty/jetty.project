//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.Scheduler;

/**
 * <p>Partial implementation of {@link NetworkConnector}.</p>
 */
public abstract class AbstractNetworkConnector extends AbstractConnector implements NetworkConnector
{
    private volatile String _host;
    private volatile int _port = 0;

    public AbstractNetworkConnector(Server server, Executor executor, Scheduler scheduler, ByteBufferPool pool, SslContextFactory sslContextFactory, int acceptors)
    {
        super(server, executor, scheduler, pool, sslContextFactory, acceptors);
    }

    public void setHost(String host)
    {
        _host = host;
    }

    @Override
    public String getHost()
    {
        return _host;
    }

    public void setPort(int port)
    {
        _port = port;
    }

    @Override
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
        // Interrupting is often sufficient to close the channel
        interruptAcceptors();
    }
    

    @Override
    public <C> Future<C> shutdown(C c)
    {
        close();
        return super.shutdown(c);
    }

    @Override
    protected boolean isAccepting()
    {
        return super.isAccepting() && isOpen();
    }

    @Override
    public String toString()
    {
        return String.format("%s@%s:%d",
                getClass().getSimpleName(),
                getHost() == null ? "0.0.0.0" : getHost(),
                getLocalPort() <= 0 ? getPort() : getLocalPort());
    }
}
