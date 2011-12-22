package org.eclipse.jetty.io;

import java.io.IOException;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;


public abstract class AbstractConnection implements Connection
{
    private static final Logger LOG = Log.getLogger(AbstractConnection.class);

    private final long _timeStamp;
    protected final EndPoint _endp;

    public AbstractConnection(EndPoint endp)
    {
        _endp=(EndPoint)endp;
        _timeStamp = System.currentTimeMillis();
    }

    public AbstractConnection(EndPoint endp,long timestamp)
    {
        _endp=(EndPoint)endp;
        _timeStamp = timestamp;
    }

    public long getTimeStamp()
    {
        return _timeStamp;
    }

    public EndPoint getEndPoint()
    {
        return _endp;
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
