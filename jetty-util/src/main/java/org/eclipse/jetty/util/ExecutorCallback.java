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

import java.util.concurrent.Executor;

public abstract class ExecutorCallback<C> implements Callback<C>
{
    private final ForkInvoker<C> _invoker;
    private final Executor _executor;

    public ExecutorCallback(Executor executor)
    {
        this(executor, 4);
    }

    public ExecutorCallback(Executor executor, int maxRecursion)
    {
        _executor = executor;
        _invoker = new ExecutorCallbackInvoker(maxRecursion);
    }

    @Override
    public final void completed(final C context)
    {
        // Should we execute?
        if (alwaysDispatchCompletion())
        {
            _invoker.fork(context);
        }
        else
        {
            _invoker.invoke(context);
        }
    }

    protected abstract void onCompleted(C context);

    @Override
    public final void failed(final C context, final Throwable x)
    {
        // Always execute failure
        Runnable runnable = new Runnable()
        {
            @Override
            public void run()
            {
                onFailed(context, x);
            }

            @Override
            public String toString()
            {
                return String.format("ExecutorCallback@%x{%s,%s}", hashCode(), context, x);
            }
        };

        if (_executor == null)
            new Thread(runnable).start();
        else
            _executor.execute(runnable);
    }

    protected void onFailed(C context, Throwable x)
    {
    }

    protected boolean alwaysDispatchCompletion()
    {
        return _executor != null;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", getClass(), hashCode());
    }

    private class ExecutorCallbackInvoker extends ForkInvoker<C> implements Runnable
    {
        private ExecutorCallbackInvoker(int maxInvocations)
        {
            super(maxInvocations);
        }

        @Override
        public void fork(final C context)
        {
            _executor.execute(context == null ? this : new Runnable()
            {
                @Override
                public void run()
                {
                    call(context);
                }

                @Override
                public String toString()
                {
                    return String.format("ExecutorCallback@%x{%s}", hashCode(), context);
                }
            });
        }

        @Override
        public void call(C context)
        {
            onCompleted(context);
        }

        @Override
        public void run()
        {
            call(null);
        }
    }
}
