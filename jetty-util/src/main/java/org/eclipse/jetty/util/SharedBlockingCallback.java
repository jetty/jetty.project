//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.TimeoutException;
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
    static final Logger LOG = Log.getLogger(SharedBlockingCallback.class);

    final ReentrantLock _lock = new ReentrantLock();
    final Condition _idle = _lock.newCondition();
    final Condition _complete = _lock.newCondition();


    private static Throwable IDLE = new ConstantThrowable("IDLE");

    private static Throwable SUCCEEDED = new ConstantThrowable("SUCCEEDED");

    private static Throwable FAILED = new ConstantThrowable("FAILED");

    Blocker _blocker;
    
    public SharedBlockingCallback()
    {
        _blocker=new Blocker();
    }
    
    protected long getIdleTimeout()
    {
        return -1;
    }
    
    public Blocker acquire() throws IOException
    {
        _lock.lock();
        long idle = getIdleTimeout();
        try
        {
            while (_blocker._state != IDLE)
            {
                if (idle>0 && (idle < Long.MAX_VALUE/2))
                {
                    // Wait a little bit longer than the blocker might block
                    if (!_idle.await(idle*2,TimeUnit.MILLISECONDS))
                        throw new IOException(new TimeoutException());
                }
                else
                    _idle.await();
            }
            _blocker._state = null;
        }
        catch (final InterruptedException e)
        {
            throw new InterruptedIOException();
        }
        finally
        {
            _lock.unlock();
        }
        return _blocker;
    }

    protected void notComplete(Blocker blocker)
    {
        LOG.warn("Blocker not complete {}",blocker);
        if (LOG.isDebugEnabled())
            LOG.debug(new Throwable());
    }
    
    /* ------------------------------------------------------------ */
    /** A Closeable Callback.
     * Uses the auto close mechanism to check block has been called OK.
     */
    public class Blocker implements Callback, Closeable
    {
        Throwable _state = IDLE;
        
        protected Blocker()
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
                else
                    throw new IllegalStateException(_state);
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
                    if (cause==null)
                        _state=FAILED;
                    else if (cause instanceof BlockerTimeoutException)
                        // Not this blockers timeout
                        _state=new IOException(cause);
                    else 
                        _state=cause;
                    _complete.signalAll();
                }
                else 
                    throw new IllegalStateException(_state);
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
            long idle = getIdleTimeout();
            try
            {
                while (_state == null)
                {
                    if (idle>0 && (idle < Long.MAX_VALUE/2))
                    {
                        // Wait a little bit longer than expected callback idle timeout
                        if (!_complete.await(idle+idle/2,TimeUnit.MILLISECONDS))
                            // The callback has not arrived in sufficient time.
                            // We will synthesize a TimeoutException 
                            _state=new BlockerTimeoutException();
                    }
                    else
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
                throw new InterruptedIOException();
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
                    notComplete(this);
            }
            finally
            {
                try 
                {
                    // If the blocker timed itself out, remember the state
                    if (_state instanceof BlockerTimeoutException)
                        // and create a new Blocker
                        _blocker=new Blocker();
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
                return String.format("%s@%x{%s}",Blocker.class.getSimpleName(),hashCode(),_state);
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
}
