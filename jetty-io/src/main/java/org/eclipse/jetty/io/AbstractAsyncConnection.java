package org.eclipse.jetty.io;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExecutorCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


public abstract class AbstractAsyncConnection implements AsyncConnection
{
    private static final Logger LOG = Log.getLogger(AbstractAsyncConnection.class);
    private final AsyncEndPoint _endp;
    private final Callback<Void> _readCallback;
    private final AtomicBoolean _readInterested = new AtomicBoolean();

    /* ------------------------------------------------------------ */
    public AbstractAsyncConnection(AsyncEndPoint endp,Executor executor)
    {
        this(endp,executor,false);
    }
    
    /* ------------------------------------------------------------ */
    public AbstractAsyncConnection(AsyncEndPoint endp,Executor executor,final boolean executeOnlyFailure)
    {
        _endp=endp;
        if (executor==null)
            throw new IllegalArgumentException();
        
        _readCallback= new ExecutorCallback<Void>(executor)
        {
            @Override
            protected void onCompleted(Void context)
            {
                if (_readInterested.compareAndSet(true,false))
                    onReadable();
            }

            @Override
            protected void onFailed(Void context, Throwable x)
            {   
                onReadFail(x);
            } 
            
            @Override
            protected boolean execute()
            {
                return !executeOnlyFailure;
            }

            @Override
            public String toString()
            {
                return String.format("AbstractAsyncConnection.RCB@%x",AbstractAsyncConnection.this.hashCode());
            }
            
            
        };
    }
    
    /* ------------------------------------------------------------ */
    public abstract void onReadable();

    /* ------------------------------------------------------------ */
    @Override
    public void onOpen()
    {
    }

    /* ------------------------------------------------------------ */
    @Override
    public void onClose()
    {
    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.io.AsyncConnection#getEndPoint()
     */
    @Override
    public AsyncEndPoint getEndPoint()
    {
        return _endp;
    }

    /* ------------------------------------------------------------ */
    public void scheduleOnReadable()
    {
        if (_readInterested.compareAndSet(false,true))
            getEndPoint().readable(null,_readCallback);
    }

    /* ------------------------------------------------------------ */
    public void onReadFail(Throwable cause)
    {
        LOG.debug("{} onReadFailed {}",this,cause);
        if (_endp.isOpen())
        {
            if (_endp.isOutputShutdown())
                _endp.close();
            else
                _endp.shutdownOutput();
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return String.format("%s@%x{%s}", getClass().getSimpleName(), hashCode(),_readInterested.get()?"R":"");
    }
}
