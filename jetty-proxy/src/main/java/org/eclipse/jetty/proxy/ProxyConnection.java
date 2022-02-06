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

package org.eclipse.jetty.proxy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.slf4j.Logger;

public abstract class ProxyConnection extends AbstractConnection
{
    protected static final Logger LOG = ConnectHandler.LOG;
    private final IteratingCallback pipe = new ProxyIteratingCallback();
    private final ByteBufferPool bufferPool;
    private final ConcurrentMap<String, Object> context;
    private ProxyConnection connection;

    protected ProxyConnection(EndPoint endp, Executor executor, ByteBufferPool bufferPool, ConcurrentMap<String, Object> context)
    {
        super(endp, executor);
        this.bufferPool = bufferPool;
        this.context = context;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return bufferPool;
    }

    public ConcurrentMap<String, Object> getContext()
    {
        return context;
    }

    public Connection getConnection()
    {
        return connection;
    }

    public void setConnection(ProxyConnection connection)
    {
        this.connection = connection;
    }

    @Override
    public void onFillable()
    {
        pipe.iterate();
    }

    protected abstract int read(EndPoint endPoint, ByteBuffer buffer) throws IOException;

    protected abstract void write(EndPoint endPoint, ByteBuffer buffer, Callback callback);

    protected void close(Throwable failure)
    {
        getEndPoint().close(failure);
    }

    @Override
    public String toConnectionString()
    {
        EndPoint endPoint = getEndPoint();
        return String.format("%s@%x[l:%s<=>r:%s]",
            getClass().getSimpleName(),
            hashCode(),
            endPoint.getLocalSocketAddress(),
            endPoint.getRemoteSocketAddress());
    }

    private class ProxyIteratingCallback extends IteratingCallback
    {
        private ByteBuffer buffer;
        private int filled;

        @Override
        protected Action process()
        {
            buffer = bufferPool.acquire(getInputBufferSize(), true);
            try
            {
                int filled = this.filled = read(getEndPoint(), buffer);
                if (LOG.isDebugEnabled())
                    LOG.debug("{} filled {} bytes", ProxyConnection.this, filled);
                if (filled > 0)
                {
                    write(connection.getEndPoint(), buffer, this);
                    return Action.SCHEDULED;
                }
                else if (filled == 0)
                {
                    bufferPool.release(buffer);
                    fillInterested();
                    return Action.IDLE;
                }
                else
                {
                    bufferPool.release(buffer);
                    connection.getEndPoint().shutdownOutput();
                    return Action.SUCCEEDED;
                }
            }
            catch (IOException x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("{} could not fill", ProxyConnection.this, x);
                bufferPool.release(buffer);
                disconnect(x);
                return Action.SUCCEEDED;
            }
        }

        @Override
        public void succeeded()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} wrote {} bytes", ProxyConnection.this, filled);
            bufferPool.release(buffer);
            super.succeeded();
        }

        @Override
        protected void onCompleteSuccess()
        {
        }

        @Override
        protected void onCompleteFailure(Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{} failed to write {} bytes", ProxyConnection.this, filled, x);
            bufferPool.release(buffer);
            disconnect(x);
        }

        private void disconnect(Throwable x)
        {
            ProxyConnection.this.close(x);
            connection.close(x);
        }
    }
}
