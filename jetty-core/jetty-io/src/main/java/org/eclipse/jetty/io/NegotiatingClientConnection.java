//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class NegotiatingClientConnection extends AbstractConnection
{
    private static final Logger LOG = LoggerFactory.getLogger(NegotiatingClientConnection.class);

    private final SSLEngine engine;
    private final ClientConnectionFactory connectionFactory;
    private final Map<String, Object> context;
    private String protocol;
    private volatile boolean completed;

    protected NegotiatingClientConnection(EndPoint endPoint, Executor executor, SSLEngine sslEngine, ClientConnectionFactory connectionFactory, Map<String, Object> context)
    {
        super(endPoint, executor);
        this.engine = sslEngine;
        this.connectionFactory = connectionFactory;
        this.context = context;
    }

    public SSLEngine getSSLEngine()
    {
        return engine;
    }

    protected Map<String, Object> getContext()
    {
        return context;
    }

    public String getProtocol()
    {
        return protocol;
    }

    protected void completed(String protocol)
    {
        this.protocol = protocol;
        this.completed = true;
    }

    @Override
    public void onOpen()
    {
        super.onOpen();
        try
        {
            getEndPoint().flush(BufferUtil.EMPTY_BUFFER);
            if (completed)
                replaceConnection();
            else
                fillInterested();
        }
        catch (Throwable x)
        {
            close();
            failConnectionPromise(x);
        }
    }

    @Override
    public void onFillable()
    {
        Throwable failure = null;
        try
        {
            while (true)
            {
                int filled = getEndPoint().fill(BufferUtil.EMPTY_BUFFER);
                if (completed)
                {
                    replaceConnection();
                    break;
                }
                else if (filled < 0)
                {
                    throw new SSLHandshakeException("Abruptly closed by peer");
                }
                else if (filled == 0)
                {
                    fillInterested();
                    break;
                }
            }
        }
        catch (Throwable x)
        {
            failure = x;
        }

        if (failure != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to fill from endpoint", failure);
            close();
            failConnectionPromise(failure);
        }
    }

    @Override
    public boolean onIdleExpired(TimeoutException timeout)
    {
        getEndPoint().close(timeout);
        failConnectionPromise(timeout);
        return false;
    }

    @Override
    public void close()
    {
        // Gentler close for SSL.
        getEndPoint().shutdownOutput();
        super.close();
    }

    private void replaceConnection() throws IOException
    {
        EndPoint endPoint = getEndPoint();
        endPoint.upgrade(connectionFactory.newConnection(endPoint, context));
    }

    private void failConnectionPromise(Throwable failure)
    {
        @SuppressWarnings("unchecked")
        Promise<Connection> promise = (Promise<Connection>)context.get(ClientConnector.CONNECTION_PROMISE_CONTEXT_KEY);
        promise.failed(failure);
    }
}
