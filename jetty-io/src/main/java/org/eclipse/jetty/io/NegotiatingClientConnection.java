//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLEngine;

import org.eclipse.jetty.util.BufferUtil;
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

    public String getProtocol()
    {
        return protocol;
    }

    protected void completed(String protocol)
    {
        this.protocol = protocol;
        completed = true;
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
            // TODO: should we not fail the promise in the context here?
            throw new RuntimeIOException(x);
        }
    }

    @Override
    public void onFillable()
    {
        while (true)
        {
            int filled = fill();
            if (completed || filled < 0)
            {
                replaceConnection();
                break;
            }
            if (filled == 0)
            {
                fillInterested();
                break;
            }
        }
    }

    private int fill()
    {
        try
        {
            return getEndPoint().fill(BufferUtil.EMPTY_BUFFER);
        }
        catch (IOException x)
        {
            LOG.debug("Unable to fill from endpoint", x);
            close();
            return -1;
        }
    }

    private void replaceConnection()
    {
        EndPoint endPoint = getEndPoint();
        try
        {
            endPoint.upgrade(connectionFactory.newConnection(endPoint, context));
        }
        catch (Throwable x)
        {
            LOG.debug("Unable to replace connection", x);
            close();
        }
    }

    @Override
    public void close()
    {
        // Gentler close for SSL.
        getEndPoint().shutdownOutput();
        super.close();
    }
}
