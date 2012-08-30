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
    private final static ThreadLocal<Integer> __calls = new ThreadLocal<Integer>()
    {
        @Override
        protected Integer initialValue()
        {
            return 0;
        }
    };

    private final int _maxRecursion;
    private final Executor _executor;
    private final Runnable _onNullContextCompleted = new Runnable()
    {
        @Override
        public void run() { onCompleted(null); }
    };

    public ExecutorCallback(Executor executor)
    {
        this(executor,4);
    }

    public ExecutorCallback(Executor executor,int maxRecursion)
    {
        _executor=executor;
        _maxRecursion=maxRecursion;
    }

    @Override
    public final void completed(final C context)
    {
        // Should we execute?
        if (!shouldDispatchCompletion())
        {
            // Do we have a recursion limit?
            if (_maxRecursion<=0)
            {
                // No, so just call it directly
                onCompleted(context);
                return;
            }
            else
            {
                // Has this thread exceeded the recursion limit
                Integer calls=__calls.get();
                if (calls<_maxRecursion)
                {
                    // No, so increment recursion count, call, then decrement
                    try
                    {
                        __calls.set(calls+1);
                        onCompleted(context);
                        return;
                    }
                    finally
                    {
                        __calls.set(calls);
                    }
                }
            }
        }

        // fallen through to here so execute
        _executor.execute(context==null?_onNullContextCompleted:new Runnable()
        {
            @Override
            public void run() { onCompleted(context);}
        });
    }

    protected abstract void onCompleted(C context);

    @Override
    public final void failed(final C context, final Throwable x)
    {
        // Always execute failure
        Runnable runnable=new Runnable()
        {
            @Override
            public void run() { onFailed(context,x);}
        };

        if (_executor==null)
            new Thread(runnable).start();
        else
            _executor.execute(runnable);
    }

    protected void onFailed(C context, Throwable x)
    {
    }

    protected boolean shouldDispatchCompletion()
    {
        return _executor!=null;
    }
}
