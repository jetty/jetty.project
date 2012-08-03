package org.eclipse.jetty.io;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ReadPendingException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.Callback;


/* ------------------------------------------------------------ */
/** 
 * A Utility class to help implement {@link EndPoint#fillInterested(Object, Callback)}
 * by keeping state and calling the context and callback objects.
 * 
 */
public abstract class FillInterest
{
    private final AtomicBoolean _interested = new AtomicBoolean(false);
    private volatile Callback<Object> _callback;
    private Object _context;

    /* ------------------------------------------------------------ */
    protected FillInterest()
    {
    }

    /* ------------------------------------------------------------ */
    /** Call to register interest in a callback when a read is possible.
     * The callback will be called either immediately if {@link #needsFill()} 
     * returns true or eventually once {@link #fillable()} is called.
     * @param context
     * @param callback
     * @throws ReadPendingException
     */
    public <C> void register(C context, Callback<C> callback) throws ReadPendingException
    {
        if (!_interested.compareAndSet(false,true))
            throw new ReadPendingException();
        _context=context;
        _callback=(Callback<Object>)callback;
        try
        {
            if (needsFill())
                fillable();
        }
        catch(IOException e)
        {
            onFail(e);
        }
    }

    /* ------------------------------------------------------------ */
    /** Call to signal that a read is now possible.
     */
    public void fillable()
    {
        if (_interested.compareAndSet(true,false))
        {
            Callback<Object> callback=_callback;
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
    public void onFail(Throwable cause)
    {
        if (_interested.compareAndSet(true,false))
        {
            Callback<Object> callback=_callback;
            Object context=_context;
            _callback=null;
            _context=null;
            callback.failed(context,cause);
        }
    }
    
    /* ------------------------------------------------------------ */
    public void onClose()
    {
        if (_interested.compareAndSet(true,false))
        {
            Callback<Object> callback=_callback;
            Object context=_context;
            _callback=null;
            _context=null;
            callback.failed(context,new ClosedChannelException());
        }
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return String.format("FillInterest@%x{%b,%s,%s}",hashCode(),_interested.get(),_callback,_context);
    }
    
    /* ------------------------------------------------------------ */
    /** Register the read interest 
     * Abstract method to be implemented by the Specific ReadInterest to
     * enquire if a read is immediately possible and if not to schedule a future
     * call to {@link #fillable()} or {@link #onFail(Throwable)}
     * @return true if a read is possible
     * @throws IOException
     */
    abstract protected boolean needsFill() throws IOException;
    
    
}
