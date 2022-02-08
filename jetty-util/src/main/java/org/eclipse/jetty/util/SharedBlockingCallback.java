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

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provides a reusable {@link Callback} that can block the thread
 * while waiting to be completed.
 * <p>
 * A typical usage pattern is:
 * <pre>
 * void someBlockingCall(Object... args) throws IOException
 * {
 *     try(Blocker blocker = sharedBlockingCallback.acquire())
 *     {
 *         someAsyncCall(args, blocker);
 *         blocker.block();
 *     }
 * }
 * </pre>
 */
public class SharedBlockingCallback
{
    private static final Logger LOG = LoggerFactory.getLogger(SharedBlockingCallback.class);

    private static final Throwable IDLE = new ConstantThrowable("IDLE");
    private static final Throwable SUCCEEDED = new ConstantThrowable("SUCCEEDED");

    private static final Throwable FAILED = new ConstantThrowable("FAILED");

    private final ReentrantLock _lock = new ReentrantLock();
    private final Condition _idle = _lock.newCondition();
    private final Condition _complete = _lock.newCondition();
    private Blocker _blocker = new Blocker();

    public Blocker acquire() throws IOException
    {
        _lock.lock();
        try
        {
            while (_blocker._state != IDLE)
            {
                _idle.await();
            }
            _blocker._state = null;
            return _blocker;
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

    public boolean fail(Throwable cause)
    {
        Objects.requireNonNull(cause);
        _lock.lock();
        try
        {
            if (_blocker._state == null)
            {
                _blocker._state = new BlockerFailedException(cause);
                _complete.signalAll();
                return true;
            }
        }
        finally
        {
            _lock.unlock();
        }
        return false;
    }

    protected void notComplete(Blocker blocker)
    {
        LOG.warn("Blocker not complete {}", blocker);
        if (LOG.isDebugEnabled())
            LOG.debug("Blocker not complete stacktrace", new Throwable());
    }

    /**
     * A Closeable Callback.
     * Uses the auto close mechanism to check block has been called OK.
     * <p>Implements {@link Callback} because calls to this
     * callback do not blocak, rather they wakeup the thread that is blocked
     * in {@link #block()}
     */
    public class Blocker implements Callback, Closeable
    {
        private Throwable _state = IDLE;

        protected Blocker()
        {
        }

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
                if (_state == null)
                {
                    _state = SUCCEEDED;
                    _complete.signalAll();
                }
                else
                {
                    LOG.warn("Succeeded after {}", _state.toString());
                    if (LOG.isDebugEnabled())
                        LOG.debug("State", _state);
                }
            }
            finally
            {
                _lock.unlock();
            }
        }

        @Override
        public void failed(Throwable cause)
        {
            _lock.lock();
            try
            {
                if (_state == null)
                {
                    if (cause == null)
                        _state = FAILED;
                    else if (cause instanceof BlockerTimeoutException)
                        // Not this blockers timeout
                        _state = new IOException(cause);
                    else
                        _state = cause;
                    _complete.signalAll();
                }
                else if (_state instanceof BlockerTimeoutException || _state instanceof BlockerFailedException)
                {
                    // Failure arrived late, block() already
                    // modified the state, nothing more to do.
                    if (LOG.isDebugEnabled())
                        LOG.debug("Failed after {}", _state);
                }
                else
                {
                    String msg = String.format("Failed after %s: %s", _state, cause);
                    LOG.warn(msg);
                    if (LOG.isDebugEnabled())
                    {
                        LOG.debug(msg, _state);
                        LOG.debug(msg, cause);
                    }
                }
            }
            finally
            {
                _lock.unlock();
            }
        }

        /**
         * Block until the Callback has succeeded or failed and after the return leave in the state to allow reuse. This is useful for code that wants to
         * repeatable use a FutureCallback to convert an asynchronous API to a blocking API.
         *
         * @throws IOException if exception was caught during blocking, or callback was cancelled
         */
        public void block() throws IOException
        {
            _lock.lock();
            try
            {
                while (_state == null)
                {
                    _complete.await();
                }

                if (_state == SUCCEEDED)
                    return;
                if (_state == IDLE)
                    throw new IllegalStateException("IDLE");
                if (_state instanceof IOException)
                    throw (IOException)_state;
                if (_state instanceof CancellationException)
                    throw (CancellationException)_state;
                if (_state instanceof RuntimeException)
                    throw (RuntimeException)_state;
                if (_state instanceof Error)
                    throw (Error)_state;
                throw new IOException(_state);
            }
            catch (final InterruptedException e)
            {
                _state = e;
                throw new InterruptedIOException();
            }
            finally
            {
                _lock.unlock();
            }
        }

        /**
         * Check the Callback has succeeded or failed and after the return leave in the state to allow reuse.
         */
        @Override
        public void close()
        {
            _lock.lock();
            try
            {
                if (_state == IDLE)
                    throw new IllegalStateException("IDLE");
                if (_state == null)
                    notComplete(this);
            }
            finally
            {
                try
                {
                    // If we have a failure
                    if (_state != null && _state != SUCCEEDED)
                        // create a new Blocker
                        _blocker = new Blocker();
                    else
                        // else reuse Blocker
                        _state = IDLE;
                    _idle.signalAll();
                    _complete.signalAll();
                }
                finally
                {
                    _lock.unlock();
                }
            }
        }

        @Override
        public String toString()
        {
            _lock.lock();
            try
            {
                return String.format("%s@%x{%s}", Blocker.class.getSimpleName(), hashCode(), _state);
            }
            finally
            {
                _lock.unlock();
            }
        }
    }

    private static class BlockerTimeoutException extends TimeoutException
    {
    }

    private static class BlockerFailedException extends Exception
    {
        public BlockerFailedException(Throwable cause)
        {
            super(cause);
        }
    }
}
