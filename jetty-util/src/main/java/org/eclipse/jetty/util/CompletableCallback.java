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

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A callback to be used by driver code that needs to know whether the callback has been
 * succeeded or failed (that is, completed) just after the asynchronous operation or not,
 * typically because further processing depends on the callback being completed.
 * The driver code competes with the asynchronous operation to complete the callback.
 * <p />
 * If the callback is already completed, the driver code continues the processing,
 * otherwise it suspends it. If it is suspended, the callback will be completed some time
 * later, and {@link #resume()} or {@link #abort(Throwable)} will be called to allow the
 * application to resume the processing.
 * <p />
 * Typical usage:
 * <pre>
 * CompletableCallback callback = new CompletableCallback()
 * {
 *     &#64;Override
 *     public void resume()
 *     {
 *         // continue processing
 *     }
 *
 *     &#64;Override
 *     public void abort(Throwable failure)
 *     {
 *         // abort processing
 *     }
 * }
 * asyncOperation(callback);
 * boolean completed = callback.tryComplete();
 * if (completed)
 *     // suspend processing, async operation not done yet
 * else
 *     // continue processing, async operation already done
 * </pre>
 */
public abstract class CompletableCallback implements Callback
{
    private final AtomicBoolean completed = new AtomicBoolean();

    @Override
    public void succeeded()
    {
        if (!tryComplete())
            resume();
    }

    @Override
    public void failed(Throwable x)
    {
        if (!tryComplete())
            abort(x);
    }

    /**
     * Callback method invoked when this callback is succeeded
     * <em>after</em> a first call to {@link #tryComplete()}.
     */
    public abstract void resume();

    /**
     * Callback method invoked when this callback is failed
     * <em>after</em> a first call to {@link #tryComplete()}.
     */
    public abstract void abort(Throwable failure);

    /**
     * Tries to complete this callback; driver code should call
     * this method once <em>after</em> the asynchronous operation
     * to detect whether the asynchronous operation has already
     * completed or not.
     *
     * @return whether the attempt to complete was successful.
     */
    public boolean tryComplete()
    {
        return completed.compareAndSet(false, true);
    }
}
