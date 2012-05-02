package org.eclipse.jetty.io;

import java.util.concurrent.ExecutionException;

import org.eclipse.jetty.util.thread.ThreadPool;

public class CompleteIOFuture implements IOFuture
{
    private final boolean _ready;
    private final Throwable _cause;
   
    public final static CompleteIOFuture COMPLETE=new CompleteIOFuture(); 
    
    public CompleteIOFuture()
    {
        _ready=true;
        _cause=null;
    }
    
    public CompleteIOFuture(Throwable cause)
    {
        _ready=false;
        _cause=cause;
    }
    
    @Override
    public boolean isReady() throws ExecutionException
    {
        if (_ready)
            return true;
        
        throw new ExecutionException(_cause);
    }

    @Override
    public void cancel() throws UnsupportedOperationException
    {
        throw new UnsupportedOperationException();
    }

    @Override
    public void await() throws ExecutionException
    {
        isReady();
    }

    @Override
    public void setCallback(final Callback callback)
    {
        dispatch(new Runnable()
        {
            @Override
            public void run()
            {
                if (_ready)
                    callback.onReady();
                else
                    callback.onFail(_cause);
            }
        });
    }
    
    protected void dispatch(Runnable callback)
    {
        callback.run();
    }

    @Override
    public boolean isComplete()
    {
        return true;
    }

    @Override
    public String toString()
    {
        return String.format("CIOF@%x{r=%b,c=%s}",
                hashCode(),
                _ready,
                _cause);
    }
}
