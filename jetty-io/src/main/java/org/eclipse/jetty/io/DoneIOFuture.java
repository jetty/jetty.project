package org.eclipse.jetty.io;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.util.Callback;

public class DoneIOFuture implements IOFuture
{
    private final boolean _ready;
    private final Throwable _cause;

    public final static DoneIOFuture COMPLETE=new DoneIOFuture();

    public DoneIOFuture()
    {
        this(true,null);
    }

    public DoneIOFuture(Throwable cause)
    {
        this(false,cause);
    }

    private DoneIOFuture(boolean ready, Throwable cause)
    {
        _ready=false;
        _cause=cause;
    }

    @Override
    public boolean isComplete() throws ExecutionException
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
        isComplete();
    }

    @Override
    public boolean block(long timeout, TimeUnit units) throws ExecutionException
    {
        return isComplete();
    }

    @Override
    public <C> void setCallback(final Callback<C> callback, final C context)
    {
        dispatch(new Runnable()
        {
            @Override
            public void run()
            {
                if (_ready)
                    callback.completed(context);
                else
                    callback.failed(context, _cause);
            }
        });
    }

    protected void dispatch(Runnable callback)
    {
        new Thread(callback).start();
    }

    @Override
    public boolean isDone()
    {
        return true;
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x{%s}",
                getClass().getSimpleName(),
                hashCode(),
                _ready?"R":_cause);
    }
}
