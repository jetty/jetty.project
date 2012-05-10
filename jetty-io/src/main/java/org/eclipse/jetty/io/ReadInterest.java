package org.eclipse.jetty.io;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadPendingException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.Callback;


/* ------------------------------------------------------------ */
/** 
 * A Utility class to help implement {@link AsyncEndPoint#readable(Object, Callback)}
 * by keeping and calling the context and callback objects.
 */
public abstract class ReadInterest
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
        try
        {
            if (readIsPossible())
                readable();
        }
        catch(IOException e)
        {
            failed(e);
        }
    }

   
    /* ------------------------------------------------------------ */
    public void readable()
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
    abstract protected boolean readIsPossible() throws IOException;
    
}
