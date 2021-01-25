//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.util.concurrent.atomic.AtomicReference;

/**
 * <p>
 * A callback to be used by driver code that needs to know whether the callback has been
 * succeeded or failed (that is, completed) just after the asynchronous operation or not,
 * typically because further processing depends on the callback being completed.
 * The driver code competes with the asynchronous operation to complete the callback.
 * </p>
 * <p>
 * If the callback is already completed, the driver code continues the processing,
 * otherwise it suspends it. If it is suspended, the callback will be completed some time
 * later, and {@link #resume()} or {@link #abort(Throwable)} will be called to allow the
 * application to resume the processing.
 * </p>
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
 *
 * @deprecated not used anymore
 */
@Deprecated
public abstract class CompletableCallback implements Callback
{
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);

    @Override
    public void succeeded()
    {
        while (true)
        {
            State current = state.get();
            switch (current)
            {
                case IDLE:
                {
                    if (state.compareAndSet(current, State.SUCCEEDED))
                        return;
                    break;
                }
                case COMPLETED:
                {
                    if (state.compareAndSet(current, State.SUCCEEDED))
                    {
                        resume();
                        return;
                    }
                    break;
                }
                case FAILED:
                {
                    return;
                }
                default:
                {
                    throw new IllegalStateException(current.toString());
                }
            }
        }
    }

    @Override
    public void failed(Throwable x)
    {
        while (true)
        {
            State current = state.get();
            switch (current)
            {
                case IDLE:
                case COMPLETED:
                {
                    if (state.compareAndSet(current, State.FAILED))
                    {
                        abort(x);
                        return;
                    }
                    break;
                }
                case FAILED:
                {
                    return;
                }
                default:
                {
                    throw new IllegalStateException(current.toString());
                }
            }
        }
    }

    /**
     * Callback method invoked when this callback is succeeded
     * <em>after</em> a first call to {@link #tryComplete()}.
     */
    public abstract void resume();

    /**
     * Callback method invoked when this callback is failed.
     *
     * @param failure the throwable reprsenting the callback failure
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
        while (true)
        {
            State current = state.get();
            switch (current)
            {
                case IDLE:
                {
                    if (state.compareAndSet(current, State.COMPLETED))
                        return true;
                    break;
                }
                case SUCCEEDED:
                case FAILED:
                {
                    return false;
                }
                default:
                {
                    throw new IllegalStateException(current.toString());
                }
            }
        }
    }

    private enum State
    {
        IDLE, SUCCEEDED, FAILED, COMPLETED
    }
}
