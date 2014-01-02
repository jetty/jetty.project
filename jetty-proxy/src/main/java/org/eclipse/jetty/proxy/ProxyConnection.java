//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
    private final ForkInvoker<Void> invoker = new ProxyForkInvoker();
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
        final ByteBuffer buffer = getByteBufferPool().acquire(getInputBufferSize(), true);
        try
        {
            final int filled = read(getEndPoint(), buffer);
            LOG.debug("{} filled {} bytes", this, filled);
            if (filled > 0)
            {
                write(getConnection().getEndPoint(), buffer, new Callback()
                {
                    @Override
                    public void succeeded()
                    {
                        LOG.debug("{} wrote {} bytes", this, filled);
                        bufferPool.release(buffer);
                        invoker.invoke(null);
                    }

                    @Override
                    public void failed(Throwable x)
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

    protected abstract int read(EndPoint endPoint, ByteBuffer buffer) throws IOException;

    protected abstract void write(EndPoint endPoint, ByteBuffer buffer, Callback callback);

    @Override
    public String toString()
    {
        return String.format("%s[l:%d<=>r:%d]",
                super.toString(),
                getEndPoint().getLocalAddress().getPort(),
                getEndPoint().getRemoteAddress().getPort());
    }

    private class ProxyForkInvoker extends ForkInvoker<Void> implements Runnable
    {
        private ProxyForkInvoker()
        {
            super(4);
        }

        @Override
        public void fork(Void arg)
        {
            getExecutor().execute(this);
        }
        
        @Override
        public void run()
        {
            onFillable();
        }

        @Override
        public void call(Void arg)
        {
            onFillable();
        }
    }
}
