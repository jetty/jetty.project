package org.eclipse.jetty.io;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.thread.ThreadPool;

public class CompletedIOFuture implements IOFuture
{
    private final boolean _ready;
    private final Throwable _cause;
   
    public final static CompletedIOFuture COMPLETE=new CompletedIOFuture(); 
    
    public CompletedIOFuture()
    {
        _ready=true;
        _cause=null;
    }
    
    public CompletedIOFuture(Throwable cause)
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
    public void block() throws ExecutionException
    {
        isReady();
    }
    
    @Override
    public boolean block(long timeout, TimeUnit units) throws ExecutionException
    {
        return isReady();
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
