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
    private volatile Callback _callback;
    private Object _context;

    /* ------------------------------------------------------------ */
    protected ReadInterest()
    {
    }

    /* ------------------------------------------------------------ */
    /** Call to register interest in a callback when a read is possible.
     * The callback will be called either immediately if {@link #readInterested()} 
     * returns true or eventually once {@link #readable()} is called.
     * @param context
     * @param callback
     * @throws ReadPendingException
     */
    public <C> void registerInterest(C context, Callback<C> callback) throws ReadPendingException
    {
        if (!_interested.compareAndSet(false,true))
            throw new ReadPendingException();
        _context=context;
        _callback=callback;
        try
        {
            if (readInterested())
                readable();
        }
        catch(IOException e)
        {
            failed(e);
        }
    }

    /* ------------------------------------------------------------ */
    /** Call to signal that a read is now possible.
     */
    public void readable()
    {
        if (_interested.compareAndSet(true,false))
        {
            Callback callback=_callback;
            Object context=_context;
            _callback=null;
            _context=null;
            callback.completed(context);
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if a read callback has been registered
     */
    public boolean isInterested()
    {
        return _interested.get();
    }
    
    /* ------------------------------------------------------------ */
    /** Call to signal a failure to a registered interest
     */
    public void failed(Throwable cause)
    {
        if (_interested.compareAndSet(true,false))
        {
            Callback callback=_callback;
            Object context=_context;
            _callback=null;
            _context=null;
            callback.failed(context,cause);
        }
    }
    
    /* ------------------------------------------------------------ */
    public void close()
    {
        if (_interested.compareAndSet(true,false))
        {
            Callback callback=_callback;
            Object context=_context;
            _callback=null;
            _context=null;
            callback.failed(context,new ClosedChannelException());
        }
    }
    
    /* ------------------------------------------------------------ */
    public String toString()
    {
        return String.format("ReadInterest@%x{%b,%s,%s}",hashCode(),_interested.get(),_callback,_context);
    }
    
    /* ------------------------------------------------------------ */
    /** Is a read interest satisfied now? 
     * Abstract method to be implemented by the Specific ReadInterest to
     * enquire if a read is immediately possible
     * @return true if a read is possible
     * @throws IOException
     */
    abstract protected boolean readInterested() throws IOException;
    
    
}
