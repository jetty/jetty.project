package org.eclipse.jetty.io;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


public abstract class AbstractAsyncConnection implements AsyncConnection
{
    private static final Logger LOG = Log.getLogger(AbstractAsyncConnection.class);
    protected final AsyncEndPoint _endp;
    
    private final IOFuture.Callback _readCallback = new ReadCallback();
    

    public AbstractAsyncConnection(AsyncEndPoint endp)
    {
        _endp=endp;
    }
    
    @Override
    public AsyncEndPoint getEndPoint()
    {
        return _endp;
    }

    
    @Override
    public void onIdleExpired(long idleForMs)
    {
        try
        {
            LOG.debug("onIdleExpired {}ms {} {}",idleForMs,this,_endp);
            if (_endp.isOutputShutdown())
                _endp.close();
            else
                _endp.shutdownOutput();
        }
        catch(IOException e)
        {
            LOG.ignore(e);

            try
            {
                _endp.close();
            }
            catch(IOException e2)
            {
                LOG.ignore(e2);
            }
        }
    }
        
    @Override
    public IOFuture scheduleOnReadable()
    {
        IOFuture read=getEndPoint().readable();
        read.setCallback(_readCallback);
        return read;
    }
    
    @Override
    public void onReadFail(Throwable cause)
    {
        LOG.debug("read failed: "+cause);
    }
    
    
    @Override
    public String toString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
    

    private class ReadCallback implements IOFuture.Callback
    {
        @Override
        public void onReady()
        {
            onReadable();
        }

        @Override
        public void onFail(Throwable cause)
        {
            onReadFail(cause);
        }
        
        @Override
        public String toString()
        {
            return String.format("AAC$ReadCB@%x",hashCode());
        }
    }

}
