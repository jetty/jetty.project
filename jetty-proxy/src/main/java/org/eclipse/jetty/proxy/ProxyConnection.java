//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.util.log.Logger;

public abstract class ProxyConnection extends AbstractConnection
{
    protected static final Logger LOG = ConnectHandler.LOG;
    private final IteratingCallback pipe = new ProxyIteratingCallback();
    private final ByteBufferPool bufferPool;
    private final ConcurrentMap<String, Object> context;
    private Connection connection;

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

    public void setConnection(Connection connection)
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

    @Override
    public String toConnectionString()
    {
        return String.format("%s@%x[l:%d<=>r:%d]",
            getClass().getSimpleName(),
            hashCode(),
            getEndPoint().getLocalAddress().getPort(),
            getEndPoint().getRemoteAddress().getPort());
    }

    private class ProxyIteratingCallback extends IteratingCallback
    {
        private ByteBuffer buffer;
        private int filled;

        @Override
        protected Action process() throws Exception
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
                    LOG.debug(ProxyConnection.this + " could not fill", x);
                bufferPool.release(buffer);
                disconnect();
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
                LOG.debug(ProxyConnection.this + " failed to write " + filled + " bytes", x);
            disconnect();
        }

        private void disconnect()
        {
            bufferPool.release(buffer);
            ProxyConnection.this.close();
            connection.close();
        }
    }
}
