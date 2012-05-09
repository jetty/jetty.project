package org.eclipse.jetty.io;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


public abstract class AbstractAsyncConnection implements AsyncConnection
{
    private static final Logger LOG = Log.getLogger(AbstractAsyncConnection.class);
    private final AsyncEndPoint _endp;
    private final ReadCallback _readCallback = new ReadCallback();

    public AbstractAsyncConnection(AsyncEndPoint endp)
    {
        _endp=endp;
    }

    public abstract void onReadable();
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.io.AsyncConnection#onClose()
     */
    @Override
    public abstract void onClose();

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

    public IOFuture scheduleOnReadable()
    {
        IOFuture read=getEndPoint().readable();
        read.setCallback(_readCallback, null);
        return read;
    }

    public void onReadFail(Throwable cause)
    {
        LOG.debug("read failed: "+cause);
    }

    @Override
    public String toString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }

    private class ReadCallback implements Callback<Void>
    {
        @Override
        public void completed(Void context)
        {
            onReadable();
        }

        @Override
        public void failed(Void context, Throwable x)
        {
            onReadFail(x);
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x",getClass().getSimpleName(),hashCode());
        }
    }
}
