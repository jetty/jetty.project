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

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/* ------------------------------------------------------------ */
/**
 * A Callback for simple reusable conversion of an 
 * asynchronous API to blocking.
 * <p>
 * To avoid late redundant calls to {@link #succeeded()} or {@link #failed(Throwable)} from
 * interfering with later reuses of this class, the callback context is used to hold pass a phase indicated
 * and only a single callback per phase is allowed.
 * <p>
 * A typical usage pattern is:
 * <pre>
 * public class MyClass
 * {
 *     BlockingCallback cb = new BlockingCallback();
 *     
 *     public void blockingMethod(Object args) throws Exception
 *     {
 *         asyncMethod(args,cb);
 *         cb.block();
 *     }
 *     
 *     public <C>void asyncMethod(Object args, Callback callback)
 *     {
 *         ...
 *     }
 *  }
 */
public class SharedBlockingCallback extends BlockingCallback
{
    private static Throwable IDLE=new Throwable()
    {
        @Override
        public String toString() { return "IDLE"; }
    };
    
    private static Throwable SUCCEEDED=new Throwable()
    {
        @Override
        public String toString() { return "SUCCEEDED"; }
    };
    

    final ReentrantLock _lock = new ReentrantLock();
    Condition _idle = _lock.newCondition();
    Condition _complete = _lock.newCondition();
    Throwable _state = IDLE;
    
    
    public SharedBlockingCallback()
    {}

    public void acquire() throws IOException
    {
        _lock.lock();
        try
        {
            while (_state!=IDLE)
                _idle.await();
            _state=null;
        }
        catch (final InterruptedException e)
        {
            throw new InterruptedIOException(){{initCause(e);}};
        }
        finally
        {
            _lock.unlock();
        }
    }
    
    @Override
    public void succeeded()
    {
        _lock.lock();
        try
        {
            if (_state==null)
            {
                _state=SUCCEEDED;
                _complete.signalAll();
            }
            else if (_state==IDLE)
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
            if (_state==null)
            {
                _state=cause;
                _complete.signalAll();
            }
            else if (_state==IDLE)
                throw new IllegalStateException("IDLE");       
        }
        finally
        {
            _lock.unlock();
        }
    }

    /** Block until the Callback has succeeded or failed and 
     * after the return leave in the state to allow reuse.
     * This is useful for code that wants to repeatable use a FutureCallback to convert
     * an asynchronous API to a blocking API. 
     * @throws IOException if exception was caught during blocking, or callback was cancelled 
     */
    @Override
    public void block() throws IOException
    {
        _lock.lock();
        try
        {
            while (_state==null)
                _complete.await();
            
            if (_state==SUCCEEDED)
                return;
            if (_state==IDLE)
                throw new IllegalStateException("IDLE");
            if (_state instanceof IOException)
                throw (IOException) _state;
            if (_state instanceof CancellationException)
                throw (CancellationException) _state;
            throw new IOException(_state);
        }
        catch (final InterruptedException e)
        {
            throw new InterruptedIOException(){{initCause(e);}};
        }
        finally
        {
            _state=IDLE;
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
