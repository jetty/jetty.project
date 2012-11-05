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

package org.eclipse.jetty.util;

import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/* ------------------------------------------------------------ */
/**
 * A Callback for simple reusable conversion of an 
 * asynchronous API to blocking.
 * <p>
 * To avoid late redundant calls to {@link #completed(Integer)} or {@link #failed(Integer, Throwable)} from
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
 *         asyncMethod(args,cb.getPhase(),cb);
 *         cb.block();
 *     }
 *     
 *     public <C>void asyncMethod(Object args, C context, Callback<C> callback)
 *     {
 *         ...
 *     }
 *  }
 */
public class BlockingCallback implements Callback<Integer>
{
    private static Throwable COMPLETED=new Throwable();
    private final AtomicBoolean _done=new AtomicBoolean(false);
    private final Semaphore _semaphone = new Semaphore(0);
    private Throwable _cause;
    private volatile int _phase;
    
    public BlockingCallback()
    {}

    @Override
    public void completed(Integer phase)
    {
        if (phase==null)
            throw new IllegalStateException("Context must be getPhase()");
        if (_phase==phase.intValue() && _done.compareAndSet(false,true))
        {
            _phase++;
            _cause=COMPLETED;
            _semaphone.release();
        }
    }

    @Override
    public void failed(Integer phase, Throwable cause)
    {
        if (phase==null)
            throw new IllegalStateException("Context must be getPhase()");
        if (_phase==phase.intValue() && _done.compareAndSet(false,true))
        {
            _phase++;
            _cause=cause;
            _semaphone.release();
        }
    }
    
    public Integer getPhase()
    {
        return new Integer(_phase);
    }

    /** Block until the FutureCallback is done or cancelled and 
     * after the return leave in the state as if a {@link #reset()} had been
     * done.
     * This is useful for code that wants to repeatable use a FutureCallback to convert
     * an asynchronous API to a blocking API. 
     * @return
     * @throws InterruptedException
     * @throws IOException
     * @throws TimeoutException
     */
    public void block() throws InterruptedException, IOException, TimeoutException
    {
        _semaphone.acquire();
        try
        {
            if (_cause==COMPLETED)
                return;
            if (_cause instanceof IOException)
                throw (IOException) _cause;
            if (_cause instanceof CancellationException)
                throw (CancellationException) _cause;
            if (_cause instanceof TimeoutException)
                throw (TimeoutException) _cause;
            throw new IOException(_cause);
        }
        finally
        {
            _done.set(false);
            _cause=null;
        }
    }
    
    
    @Override
    public String toString()
    {
        return String.format("%s@%x{%b,%b}",BlockingCallback.class.getSimpleName(),hashCode(),_done.get(),_cause==COMPLETED);
    }

}
