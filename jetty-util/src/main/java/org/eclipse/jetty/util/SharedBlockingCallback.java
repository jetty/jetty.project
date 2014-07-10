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

package org.eclipse.jetty.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.NonBlockingThread;


/* ------------------------------------------------------------ */
/** Provides a reusable BlockingCallback.
 * A typical usage pattern is:
 * <pre>
 * void someBlockingCall(Object... args) throws IOException
 * {
 *   try(Blocker blocker=sharedBlockingCallback.acquire())
 *   {
 *     someAsyncCall(args,blocker);
 *     blocker.block();
 *   }
 * }
 * </pre>
 */
public class SharedBlockingCallback
{
    private static final Logger LOG = Log.getLogger(SharedBlockingCallback.class);

    
    private static Throwable IDLE = new Throwable()
    {
        @Override
        public String toString()
        {
            return "IDLE";
        }
    };

    private static Throwable SUCCEEDED = new Throwable()
    {
        @Override
        public String toString()
        {
            return "SUCCEEDED";
        }
    };
    
    private static Throwable FAILED = new Throwable()
    {
        @Override
        public String toString()
        {
            return "FAILED";
        }
    };

    final Blocker _blocker;
    
    public SharedBlockingCallback()
    {
        this(new Blocker());
    }
    
    protected SharedBlockingCallback(Blocker blocker)
    {
        _blocker=blocker;
    }
    
    public Blocker acquire() throws IOException
    {
        _blocker._lock.lock();
        try
        {
            while (_blocker._state != IDLE)
                _blocker._idle.await();
            _blocker._state = null;
        }
        catch (final InterruptedException e)
        {
            throw new InterruptedIOException()
            {
                {
                    initCause(e);
                }
            };
        }
        finally
        {
            _blocker._lock.unlock();
        }
        return _blocker;
    }

    
    /* ------------------------------------------------------------ */
    /** A Closeable Callback.
     * Uses the auto close mechanism to check block has been called OK.
     */
    public static class Blocker implements Callback, Closeable
    {
        final ReentrantLock _lock = new ReentrantLock();
        final Condition _idle = _lock.newCondition();
        final Condition _complete = _lock.newCondition();
        Throwable _state = IDLE;

        public Blocker()
        {
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
                else if (_state == IDLE)
                    throw new IllegalStateException("IDLE");
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
                    _state = cause==null?FAILED:cause;
                    _complete.signalAll();
                }
                else if (_state == IDLE)
                    throw new IllegalStateException("IDLE",cause);
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
         * @throws IOException
         *             if exception was caught during blocking, or callback was cancelled
         */
        public void block() throws IOException
        {
            if (NonBlockingThread.isNonBlockingThread())
                LOG.warn("Blocking a NonBlockingThread: ",new Throwable());
            
            _lock.lock();
            try
            {
                while (_state == null)
                {
                    // TODO remove this debug timout!
                    // This is here to help debug 435322,
                    if (!_complete.await(10,TimeUnit.MINUTES))
                    {
                        IOException x = new IOException("DEBUG timeout");
                        LOG.warn("Blocked too long (please report!!!) "+this, x);
                        _state=x;
                    }
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
                throw new InterruptedIOException()
                {
                    {
                        initCause(e);
                    }
                };
            }
            finally
            {
                _lock.unlock();
            }
        }
        
        /**
         * Check the Callback has succeeded or failed and after the return leave in the state to allow reuse.
         * 
         * @throws IOException
         *             if exception was caught during blocking, or callback was cancelled
         */
        @Override
        public void close() throws IOException
        {
            _lock.lock();
            try
            {
                if (_state == IDLE)
                    throw new IllegalStateException("IDLE");
                if (_state == null)
                    LOG.debug("Blocker not complete",new Throwable());
            }
            finally
            {
                _state = IDLE;
                _idle.signalAll();
                _lock.unlock();
            }
        }

        @Override
        public String toString()
        {
            _lock.lock();
            try
            {
                return String.format("%s@%x{%s}",SharedBlockingCallback.class.getSimpleName(),hashCode(),_state);
            }
            finally
            {
                _lock.unlock();
            }
        }
    }
}
