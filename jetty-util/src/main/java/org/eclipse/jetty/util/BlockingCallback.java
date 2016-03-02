//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * An implementation of Callback that blocks until success or failure.
 */
@Deprecated
public class BlockingCallback implements Callback.NonBlocking
{
    private static final Logger LOG = Log.getLogger(BlockingCallback.class);
    private static Throwable SUCCEEDED = new Throwable()
    {
        @Override
        public String toString() { return "SUCCEEDED"; }
    };
    
    private final CountDownLatch _latch = new CountDownLatch(1);
    private final AtomicReference<Throwable> _state = new AtomicReference<>();

    public BlockingCallback()
    {
    }

    @Override
    public void succeeded()
    {
        if (_state.compareAndSet(null,SUCCEEDED))
            _latch.countDown();
    }

    @Override
    public void failed(Throwable cause)
    {
        if (_state.compareAndSet(null,cause))
            _latch.countDown();
    }

    /**
     * Blocks until the Callback has succeeded or failed and
     * after the return leave in the state to allow reuse.
     * This is useful for code that wants to repeatable use a FutureCallback to convert
     * an asynchronous API to a blocking API. 
     * @throws IOException if exception was caught during blocking, or callback was cancelled 
     */
    public void block() throws IOException
    {
        try
        {
            _latch.await();
            Throwable state=_state.get();
            if (state==SUCCEEDED)
                return;
            if (state instanceof IOException)
                throw (IOException) state;
            if (state instanceof CancellationException)
                throw (CancellationException) state;
            throw new IOException(state);
        }
        catch (final InterruptedException e)
        {
            throw new InterruptedIOException(){{initCause(e);}};
        }
        finally
        {
            _state.set(null);
        }
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s}",BlockingCallback.class.getSimpleName(),hashCode(),_state.get());
    }
}
