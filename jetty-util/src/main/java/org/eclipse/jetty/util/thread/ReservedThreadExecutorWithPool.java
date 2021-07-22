//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.util.thread;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Exchanger;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

@ManagedObject("An alternate pool for reserved threads")
public class ReservedThreadExecutorWithPool extends AbstractLifeCycle implements TryExecutor
{
    private static final Logger LOG = Log.getLogger(ReservedThreadExecutorWithPool.class);

    private final Pool<ReservedThread> stack;
    private final Executor source;
    private final int capacity;

    private long idleTime = 1L;
    private TimeUnit idleTimeUnit = TimeUnit.MINUTES;

    public ReservedThreadExecutorWithPool(Executor source, int capacity)
    {
        this.stack = new Pool<>(Pool.StrategyType.FIRST, capacity);
        this.source = source;
        this.capacity = capacity;
    }

    @ManagedAttribute(value = "idletimeout in MS", readonly = true)
    public long getIdleTimeoutMs()
    {
        if (idleTimeUnit == null)
            return 0;
        return idleTimeUnit.toMillis(idleTime);
    }

    /**
     * Set the idle timeout for shrinking the reserved thread pool
     *
     * @param idleTime Time to wait before shrinking, or 0 for no timeout.
     * @param idleTimeUnit Time units for idle timeout
     */
    public void setIdleTimeout(long idleTime, TimeUnit idleTimeUnit)
    {
        if (isRunning())
            throw new IllegalStateException();
        this.idleTime = idleTime;
        this.idleTimeUnit = idleTimeUnit;
    }

    @Override
    public boolean tryExecute(Runnable task)
    {
        Pool<ReservedThread>.Entry entry = stack.acquire();
        ReservedThread reservedThread = entry == null ? null : entry.getPooled();
        if (reservedThread != null)
        {
            return reservedThread.offer(task);
        }
        else
        {
            if (stack.size() < capacity)
            {
                Pool<ReservedThread>.Entry reservedEntry = stack.reserve();
                if (reservedEntry != null)
                    source.execute(new ReservedThread(reservedEntry));
            }
            return false;
        }
    }

    @Override
    protected void doStop() throws Exception
    {
        stack.close();
    }

    private class ReservedThread implements Runnable, Closeable
    {
        private final Exchanger<Runnable> exchanger = new Exchanger<>();
        private final Pool<ReservedThread>.Entry entry;

        public ReservedThread(Pool<ReservedThread>.Entry entry)
        {
            this.entry = entry;
        }

        @Override
        public void close() throws IOException
        {
            try
            {
                exchanger.exchange(STOP);
            }
            catch (InterruptedException e)
            {
                // TODO handle
            }
        }

        public boolean offer(Runnable task)
        {
            try
            {
                exchanger.exchange(task, 1, TimeUnit.SECONDS);
                return true;
            }
            catch (InterruptedException | TimeoutException e)
            {
                return false;
            }
        }

        @Override
        public void run()
        {
            entry.enable(this, false);
            while (isRunning())
            {
                try
                {
                    Runnable task = exchanger.exchange(null, idleTime, idleTimeUnit);
                    if (task == STOP)
                    {
                        entry.remove();
                        return;
                    }
                    task.run();
                    entry.release();
                }
                catch (TimeoutException e)
                {
                    entry.remove();
                    return;
                }
                catch (Throwable x)
                {
                    entry.remove();
                    LOG.warn(x);
                    return;
                }
            }
        }
    }

    private static final Runnable STOP = new Runnable()
    {
        @Override
        public void run()
        {
        }

        @Override
        public String toString()
        {
            return "STOP!";
        }
    };
}
