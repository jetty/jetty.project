package org.eclipse.jetty.util;

import java.util.concurrent.Executor;


public class ExecutorCallback<C> implements Callback<C>
{
    private final Executor _executor;
    private final Runnable _onNullContextCompleted = new Runnable()
    {
        @Override
        public void run() { onCompleted(null); }
    };
    
    public ExecutorCallback(Executor executor)
    {
        _executor=executor;
    }
    
    @Override
    public void completed(final C context)
    {   
        if (execute())
        {
            _executor.execute(context==null?
                    _onNullContextCompleted:
                        new Runnable()
            {
                @Override
                public void run() { onCompleted(context);}
            });
        }
        else
            onCompleted(context);
    }

    protected void onCompleted(C context)
    {
    }
    
   
    @Override
    public void failed(final C context, final Throwable x)
    {   
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
