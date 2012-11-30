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

public abstract class ExecutorCallback implements Callback
{
    private final ForkInvoker<Void> _invoker;
    private final Executor _executor;
    private final Runnable _onComplete=new Runnable()
    {
        @Override
        public void run()
        {
            onCompleted();
        }
    };

    public ExecutorCallback(Executor executor)
    {
        this(executor, 4);
    }

    public ExecutorCallback(Executor executor, int maxRecursion)
    {
        _executor = executor;
        _invoker = maxRecursion>0?new ExecutorCallbackInvoker(maxRecursion):null;
        if (_executor==null)
            throw new IllegalArgumentException();
    }

    @Override
    public void succeeded()
    {
        // Should we execute?
        if (_invoker==null)
        {
            _executor.execute(_onComplete);
        } 
        else if (alwaysDispatchCompletion())
        {
            _invoker.fork(null);
        }
        else
        {
            _invoker.invoke(null);
        }
    }

    protected abstract void onCompleted();

    @Override
    public void failed(final Throwable x)
    {
        // Always execute failure
        Runnable runnable = new Runnable()
        {
            @Override
            public void run()
            {
                onFailed(x);
            }

            @Override
            public String toString()
            {
                return String.format("ExecutorCallback@%x{%s}", hashCode(), x);
            }
        };

        if (_executor == null)
            new Thread(runnable).start();
        else
            _executor.execute(runnable);
    }

    protected void onFailed(Throwable x)
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

    private class ExecutorCallbackInvoker extends ForkInvoker<Void> implements Runnable
    {
        private ExecutorCallbackInvoker(int maxInvocations)
        {
            super(maxInvocations);
        }

        @Override
        public void fork(Void arg)
        {
            _executor.execute(this);
        }

        @Override
        public void call(Void arg)
        {
            onCompleted();
        }

        @Override
        public void run()
        {
            onCompleted();
        }
    }
}
