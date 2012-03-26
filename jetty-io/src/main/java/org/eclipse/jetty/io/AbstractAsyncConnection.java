package org.eclipse.jetty.io;

import java.io.IOException;

import org.eclipse.jetty.io.nio.AsyncConnection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


public abstract class AbstractAsyncConnection implements AsyncConnection
{
    private static final Logger LOG = Log.getLogger(AbstractAsyncConnection.class);

    private final long _timeStamp;
    protected final AsyncEndPoint _endp;

    public AbstractAsyncConnection(AsyncEndPoint endp)
    {
        _endp=endp;
        _timeStamp = System.currentTimeMillis();
    }

    public AbstractAsyncConnection(AsyncEndPoint endp,long timestamp)
    {
        _endp=endp;
        _timeStamp = timestamp;
    }
    
    
    @Override
    public AsyncEndPoint getAsyncEndPoint()
    {
        return _endp;
    }

    public long getTimeStamp()
    {
        return _timeStamp;
    }

    public void onIdleExpired(long idleForMs)
    {
        try
        {
            LOG.debug("onIdleExpired {}ms {} {}",idleForMs,this,_endp);
            if (_endp.isInputShutdown() || _endp.isOutputShutdown())
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

    public String toString()
    {
        return String.format("%s@%x", getClass().getSimpleName(), hashCode());
    }
}
