//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.atomic.AtomicBoolean;

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
public class BlockingCallback implements Callback
{
    private static Throwable COMPLETED=new Throwable();
    private final AtomicBoolean _done=new AtomicBoolean(false);
    private final Semaphore _semaphore = new Semaphore(0);
    private Throwable _cause;
    
    public BlockingCallback()
    {}

    @Override
    public void succeeded()
    {
        if (_done.compareAndSet(false,true))
        {
            _cause=COMPLETED;
            _semaphore.release();
        }
    }

    @Override
    public void failed(Throwable cause)
    {
        if (_done.compareAndSet(false,true))
        {
            _cause=cause;
            _semaphore.release();
        }
    }

    /** Block until the Callback has succeeded or failed and 
     * after the return leave in the state to allow reuse.
     * This is useful for code that wants to repeatable use a FutureCallback to convert
     * an asynchronous API to a blocking API. 
     * @throws IOException if exception was caught during blocking, or callback was cancelled 
     */
    public void block() throws IOException
    {
        try
        {
            _semaphore.acquire();
            if (_cause==COMPLETED)
                return;
            if (_cause instanceof IOException)
                throw (IOException) _cause;
            if (_cause instanceof CancellationException)
                throw (CancellationException) _cause;
            throw new IOException(_cause);
        }
        catch (final InterruptedException e)
        {
            throw new InterruptedIOException(){{initCause(e);}};
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
