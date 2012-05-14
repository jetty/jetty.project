package org.eclipse.jetty.util;

import java.util.concurrent.Executor;


public abstract class ExecutorCallback<C> implements Callback<C>
{
    private final static Integer ZERO = new Integer(0);
    private final static ThreadLocal<Integer> __calls = new ThreadLocal<Integer>()
            {
                @Override
                protected Integer initialValue()
                {
                    return ZERO;
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
    public void completed(final C context)
    {    
        // Should we execute?
        if (!execute())
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
    public void failed(final C context, final Throwable x)
    {   
        // Always execute failure
        _executor.execute(new Runnable()
        {
            @Override
            public void run() { onFailed(context,x);}
        });     
    }

    protected void onFailed(C context, Throwable x)
    {
    }
    
    protected boolean execute()
    {
        return _executor!=null;
    }
}
