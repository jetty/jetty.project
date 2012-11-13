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
import org.eclipse.jetty.util.ForkInvoker;
import org.eclipse.jetty.util.log.Logger;

public abstract class ProxyConnection extends AbstractConnection
{
    protected static final Logger LOG = ConnectHandler.LOG;
    private final ForkInvoker<ByteBuffer> invoker = new ProxyForkInvoker();
    private final ByteBufferPool bufferPool;
    private final ConcurrentMap<String, Object> context;
    private final ConnectHandler connectHandler;
    private Connection connection;

    protected ProxyConnection(EndPoint endp, Executor executor, ByteBufferPool bufferPool, ConcurrentMap<String, Object> context, ConnectHandler connectHandler)
    {
        super(endp, executor);
        this.bufferPool = bufferPool;
        this.context = context;
        this.connectHandler = connectHandler;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return bufferPool;
    }

    public ConcurrentMap<String, Object> getContext()
    {
        return context;
    }

    public ConnectHandler getConnectHandler()
    {
        return connectHandler;
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
        ByteBuffer buffer = getByteBufferPool().acquire(getInputBufferSize(), true);
        fill(buffer);
    }

    private void fill(final ByteBuffer buffer)
    {
        try
        {
            final int filled = connectHandler.read(getEndPoint(), buffer, getContext());
            LOG.debug("{} filled {} bytes", this, filled);
            if (filled > 0)
            {
                write(buffer, new Callback<Void>()
                {
                    @Override
                    public void completed(Void context)
                    {
                        LOG.debug("{} wrote {} bytes", this, filled);
                        buffer.clear();
                        invoker.invoke(buffer);
                    }

                    @Override
                    public void failed(Void context, Throwable x)
                    {
                        LOG.debug(this + " failed to write " + filled + " bytes", x);
                        bufferPool.release(buffer);
                        connection.close();
                    }
                });
            }
            else if (filled == 0)
            {
                bufferPool.release(buffer);
                fillInterested();
            }
            else
            {
                bufferPool.release(buffer);
                connection.getEndPoint().shutdownOutput();
            }
        }
        catch (IOException x)
        {
            LOG.debug(this + " could not fill", x);
            bufferPool.release(buffer);
            close();
            connection.close();
        }
    }

    protected void write(ByteBuffer buffer, Callback<Void> callback)
    {
        LOG.debug("{} writing {} bytes", this, buffer.remaining());
        connectHandler.write(getConnection().getEndPoint(), buffer, context, callback);
    }

    @Override
    public String toString()
    {
        return String.format("%s[l:%d<=>r:%d]",
                super.toString(),
                getEndPoint().getLocalAddress().getPort(),
                getEndPoint().getRemoteAddress().getPort());
    }

    private class ProxyForkInvoker extends ForkInvoker<ByteBuffer>
    {
        private ProxyForkInvoker()
        {
            super(4);
        }

        @Override
        public void fork(final ByteBuffer buffer)
        {
            getExecutor().execute(new Runnable()
            {
                @Override
                public void run()
                {
                    call(buffer);
                }
            });
        }

        @Override
        public void call(ByteBuffer buffer)
        {
            fill(buffer);
        }
    }
}
