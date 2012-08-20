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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;


//TODO: Simplify, get rid of DOING. Probably replace states with AtomicBoolean
public class FutureCallback<C> implements Future<C>,Callback<C>
{
    private enum State {NOT_DONE,DOING,DONE};
    private final AtomicReference<State> _state=new AtomicReference<>(State.NOT_DONE);
    private CountDownLatch _done= new CountDownLatch(1);
    private Throwable _cause;
    private C _context;
    private boolean _completed;
    
    @Override
    public void completed(C context)
    {
        if (_state.compareAndSet(State.NOT_DONE,State.DOING))
        {
            _context=context;
            _completed=true;
            if (_state.compareAndSet(State.DOING,State.DONE))
            {
                _done.countDown();
                return;
            }
        }
        else if (!isCancelled())
            throw new IllegalStateException();
    }

    @Override
    public void failed(C context, Throwable cause)
    {
        if (_state.compareAndSet(State.NOT_DONE,State.DOING))
        {
            _context=context;
            _cause=cause;
            if (_state.compareAndSet(State.DOING,State.DONE))
            {
                _done.countDown();
                return;
            }
        }
        else if (!isCancelled())
            throw new IllegalStateException();
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning)
    {
        failed(null,new CancellationException());
        return false;
    }

    @Override
    public boolean isCancelled()
    {
        return State.DONE.equals(_state.get())&&_cause instanceof CancellationException;
    }

    @Override
    public boolean isDone()
    {
        return State.DONE.equals(_state.get());
    }

    @Override
    public C get() throws InterruptedException, ExecutionException
    {
        _done.await();
        if (_completed)
            return _context;
        if (_cause instanceof CancellationException)
            throw (CancellationException) new CancellationException().initCause(_cause);
        throw new ExecutionException(_cause);
    }

    @Override
    public C get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException
    {
        if (!_done.await(timeout,unit))
            throw new TimeoutException();
        if (_completed)
            return _context;
        if (_cause instanceof TimeoutException)
            throw (TimeoutException)_cause;
        if (_cause instanceof CancellationException)
            throw (CancellationException) new CancellationException().initCause(_cause);
        throw new ExecutionException(_cause);
    }

    public static void rethrow(ExecutionException e) throws IOException
    {
        Throwable cause=e.getCause();
        if (cause instanceof IOException)
            throw (IOException)cause;
        if (cause instanceof Error)
            throw (Error)cause;
        if (cause instanceof RuntimeException)
            throw (RuntimeException)cause;
        throw new RuntimeException(cause);
    }
    
    /* ------------------------------------------------------------ */
    public String toString()
    {
        return String.format("FutureCallback@%x{%s,%b,%s}",hashCode(),_state,_completed,_context);
    }
    
}
