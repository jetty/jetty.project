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

/**
 * Utility class that splits calls to {@link #invoke(T)} into calls to {@link #fork(T)} or {@link #call(T)}
 * depending on the max number of reentrant calls to {@link #invoke(T)}.
 * <p/>
 * This class prevents {@link StackOverflowError}s in case of methods that end up invoking themselves,
 * such is common for {@link Callback#completed(Object)}.
 * <p/>
 * Typical use case is:
 * <pre>
 * public void reentrantMethod(Object param)
 * {
 *     if (condition || tooManyReenters)
 *         fork(param)
 *     else
 *         call(param)
 * }
 * </pre>
 * Calculating {@code tooManyReenters} usually involves using a {@link ThreadLocal} and algebra on the
 * number of reentrant invocations, which is factored out in this class for convenience.
 * <p />
 * The same code using this class becomes:
 * <pre>
 * private final ForkInvoker invoker = ...;
 *
 * public void reentrantMethod(Object param)
 * {
 *     invoker.invoke(param);
 * }
 * </pre>
 *
 * @param <T> the generic type of this class
 */
public abstract class ForkInvoker<T>
{
    private static final ThreadLocal<Integer> __invocations = new ThreadLocal<Integer>()
    {
        @Override
        protected Integer initialValue()
        {
            return 0;
        }
    };
    private final int _maxInvocations;

    /**
     * Creates an instance with the given max number of reentrant calls to {@link #invoke(T)}
     * <p/>
     * If {@code maxInvocations} is zero or negative, it is interpreted
     * as if the max number of reentrant calls is infinite.
     *
     * @param maxInvocations the max number of reentrant calls to {@link #invoke(T)}
     */
    public ForkInvoker(int maxInvocations)
    {
        _maxInvocations = maxInvocations;
    }

    /**
     * Invokes either {@link #fork(T)} or {@link #call(T)}.
     * If {@link #condition()} returns true, {@link #fork(T)} is invoked.
     * Otherwise, if the max number of reentrant calls is positive and the
     * actual number of reentrant invocations exceeds it, {@link #fork(T)} is invoked.
     * Otherwise, {@link #call(T)} is invoked.
     *
     * @param context the invocation context
     * @return true if {@link #fork(T)} has been called, false otherwise
     */
    public boolean invoke(T context)
    {
        boolean countInvocations = _maxInvocations > 0;
        int invocations = __invocations.get();
        if (condition() || countInvocations && invocations > _maxInvocations)
        {
            fork(context);
            return true;
        }
        else
        {
            if (countInvocations)
                __invocations.set(invocations + 1);
            try
            {
                call(context);
                return false;
            }
            finally
            {
                if (countInvocations)
                    __invocations.set(invocations);
            }
        }
    }

    /**
     * Subclasses should override this method returning true if they want
     * {@link #invoke(T)} to call {@link #fork(T)}.
     *
     * @return true if {@link #invoke(T)} should call {@link #fork(T)}, false otherwise
     */
    protected boolean condition()
    {
        return false;
    }

    /**
     * Executes the forked invocation
     *
     * @param context the invocation context
     */
    public abstract void fork(T context);

    /**
     * Executes the direct, non-forked, invocation
     *
     * @param context the invocation context
     */
    public abstract void call(T context);
}
