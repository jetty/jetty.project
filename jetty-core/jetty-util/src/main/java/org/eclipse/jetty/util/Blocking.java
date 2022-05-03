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

package org.eclipse.jetty.util;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jetty.util.thread.Invocable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class that provides blocking {@link java.lang.Runnable} and {@link org.eclipse.jetty.util.Callback}
 * instances.  These can either be shared (and mutually excluded from concurrent usage) or single usage.
 * The instances are autocloseable and will emit a warning if the instance is not completed within close.
 *
 * <h2>Non shared Runnable</h2>
 * <pre>
 *     try(Blocking.Runnable onAction = Blocking.runnable())
 *     {
 *         someMethod(onAction);
 *         onAction.block();
 *     }
 * </pre>
 *
 * <h2>Shared Runnable</h2>
 * <pre>
 *     Blocking.SharedRunnable shared = new Blocking.Shared();
 *     // ...
 *     try(Blocking.Runnable onAction = shared.runnable())
 *     {
 *         someMethod(onAction);
 *         onAction.block();
 *     }
 * </pre>
 *
 * <h2>Non shared Callback</h2>
 * <pre>
 *     try(Blocking.Callback callback = Blocking.callback())
 *     {
 *         someMethod(callback);
 *         callback.block();
 *     }
 * </pre>
 *
 * <h2>Shared Callback</h2>
 * <pre>
 *     Blocking.Shared blocker = new Blocking.Shared();
 *     // ...
 *     try(Blocking.Callback callback = blocker.callback())
 *     {
 *         someMethod(callback);
 *         callback.block();
 *     }
 * </pre>
 */
public class Blocking
{
    private static final Logger LOG = LoggerFactory.getLogger(Blocking.class);

    private static final Throwable ACQUIRED = new Throwable()
    {
        @Override
        public Throwable fillInStackTrace()
        {
            return this;
        }
    };
    private static final Throwable SUCCEEDED = new Throwable()
    {
        @Override
        public Throwable fillInStackTrace()
        {
            return this;
        }
    };

    public interface Runnable extends java.lang.Runnable, AutoCloseable, Invocable
    {
        void block() throws IOException;
    }

    public static Runnable runnable()
    {
        return new Runnable()
        {
            final CountDownLatch _complete = new CountDownLatch(1);

            @Override
            public void run()
            {
                _complete.countDown();
            }

            @Override
            public InvocationType getInvocationType()
            {
                return InvocationType.NON_BLOCKING;
            }

            @Override
            public void block() throws IOException
            {
                try
                {
                    _complete.await();
                }
                catch (Throwable t)
                {
                    throw IO.rethrow(t);
                }
            }

            @Override
            public void close()
            {
                if (_complete.getCount() != 0)
                {
                    if (LOG.isDebugEnabled())
                        LOG.warn("Blocking.Runnable incomplete", new Throwable());
                    else
                        LOG.warn("Blocking.Runnable incomplete");
                }
            }
        };
    }

    public interface Callback extends org.eclipse.jetty.util.Callback, AutoCloseable, Invocable
    {
        void block() throws IOException;

        @Override
        void close();
    }

    public static Callback callback()
    {
        return new Callback()
        {
            private final CompletableFuture<Throwable> _future = new CompletableFuture<>();

            @Override
            public InvocationType getInvocationType()
            {
                return InvocationType.NON_BLOCKING;
            }

            @Override
            public void succeeded()
            {
                _future.complete(SUCCEEDED);
            }

            @Override
            public void failed(Throwable x)
            {
                _future.complete(x == null ? new Throwable() : x);
            }

            @Override
            public void block() throws IOException
            {
                Throwable result;
                try
                {
                    result = _future.get();
                }
                catch (Throwable t)
                {
                    result = t;
                }
                if (result == SUCCEEDED)
                    return;
                throw IO.rethrow(result);
            }

            @Override
            public void close()
            {
                if (!_future.isDone())
                {
                    if (LOG.isDebugEnabled())
                        LOG.warn("Blocking.Callback incomplete", new Throwable());
                    else
                        LOG.warn("Blocking.Callback incomplete");
                }
            }
        };
    }

    /**
     * A shared reusable Blocking source.
     * TODO Review need for this, as it is currently unused.
     */
    public static class Shared
    {
        private final ReentrantLock _lock = new ReentrantLock();
        private final Condition _idle = _lock.newCondition();
        private final Condition _complete = _lock.newCondition();
        private Throwable _completed;
        private final Callback _callback = new Callback()
        {
            @Override
            public InvocationType getInvocationType()
            {
                return InvocationType.NON_BLOCKING;
            }

            @Override
            public void succeeded()
            {
                _lock.lock();
                try
                {
                    if (_completed == ACQUIRED)
                    {
                        _completed = SUCCEEDED;
                        _complete.signalAll();
                    }
                }
                finally
                {
                    _lock.unlock();
                }
            }

            @Override
            public void failed(Throwable x)
            {
                _lock.lock();
                try
                {
                    if (_completed == ACQUIRED)
                    {
                        _completed = x;
                        _complete.signalAll();
                    }
                }
                finally
                {
                    _lock.unlock();
                }
            }

            @Override
            public void block() throws IOException
            {
                _lock.lock();
                Throwable result;
                try
                {
                    while (_completed == ACQUIRED)
                    {
                        _complete.await();
                    }
                    result = _completed;
                }
                catch (Throwable t)
                {
                    result = t;
                }
                finally
                {
                    _lock.unlock();
                }
                if (result != SUCCEEDED)
                    throw IO.rethrow(result);
            }

            @Override
            public void close()
            {
                boolean completed;
                _lock.lock();
                try
                {
                    completed = _completed != ACQUIRED;
                }
                finally
                {
                    _completed = null;
                    _idle.signalAll();
                    _lock.unlock();
                }
                if (!completed)
                {
                    if (LOG.isDebugEnabled())
                        LOG.warn("Blocking.Shared incomplete", new Throwable());
                    else
                        LOG.warn("Blocking.Shared incomplete");
                }
            }
        };

        private final Runnable _runnable = new Runnable()
        {
            @Override
            public InvocationType getInvocationType()
            {
                return InvocationType.NON_BLOCKING;
            }

            @Override
            public void run()
            {
                _callback.succeeded();
            }

            @Override
            public void block() throws IOException
            {
                _callback.block();
            }

            @Override
            public void close() throws Exception
            {
                _callback.close();
            }
        };

        public Callback callback() throws IOException
        {
            _lock.lock();
            try
            {
                while (_completed != null)
                    _idle.await();
                _completed = ACQUIRED;
                return _callback;
            }
            catch (InterruptedException x)
            {
                throw new InterruptedIOException();
            }
            finally
            {
                _lock.unlock();
            }
        }

        public Runnable runnable() throws IOException
        {
            _lock.lock();
            try
            {
                while (_completed != null)
                    _idle.await();
                _completed = ACQUIRED;
                return _runnable;
            }
            catch (InterruptedException x)
            {
                throw new InterruptedIOException();
            }
            finally
            {
                _lock.unlock();
            }
        }
    }
}
