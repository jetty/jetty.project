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
        _endp=endp;
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
    /**
     * @see org.eclipse.jetty.io.AsyncConnection#onIdleExpired(long)
     */
    @Override
    public void onIdleExpired(long idleForMs)
    {
        LOG.debug("onIdleExpired {}ms {} {}",idleForMs,this,_endp);
        if (_endp.isOutputShutdown())
            _endp.close();
        else
            _endp.shutdownOutput();
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
        LOG.debug("read failed: "+cause);
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}
