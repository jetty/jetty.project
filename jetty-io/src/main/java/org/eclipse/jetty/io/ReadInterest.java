package org.eclipse.jetty.io;

import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadPendingException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.Callback;

public class ReadInterest
{
    private final AtomicBoolean _interested = new AtomicBoolean(false);
    private volatile Callback _readCallback;
    private Object _readContext;

    /* ------------------------------------------------------------ */
    protected ReadInterest()
    {
    }

    /* ------------------------------------------------------------ */
    public void readable(Object context, Callback callback) throws ReadPendingException
    {
        if (!_interested.compareAndSet(false,true))
            throw new ReadPendingException();
        _readContext=context;
        _readCallback=callback;
        if (makeInterested())
            completed();
    }

   
    /* ------------------------------------------------------------ */
    public void completed()
    {
        if (_interested.compareAndSet(true,false))
        {
            Callback callback=_readCallback;
            Object context=_readContext;
            _readCallback=null;
            _readContext=null;
            callback.completed(context);
        }
    }

    /* ------------------------------------------------------------ */
    public boolean isInterested()
    {
        return _interested.get();
    }
    
    /* ------------------------------------------------------------ */
    public void failed(Throwable cause)
    {
        if (_interested.compareAndSet(true,false))
        {
            Callback callback=_readCallback;
            Object context=_readContext;
            _readCallback=null;
            _readContext=null;
            callback.failed(context,cause);
        }
    }
    
    /* ------------------------------------------------------------ */
    public void close()
    {
        if (_interested.compareAndSet(true,false))
        {
            Callback callback=_readCallback;
            Object context=_readContext;
            _readCallback=null;
            _readContext=null;
            callback.failed(context,new ClosedChannelException());
        }
    }
    
    /* ------------------------------------------------------------ */
    protected boolean makeInterested()
    {
        throw new IllegalStateException("Unimplemented");
    }
    
}
