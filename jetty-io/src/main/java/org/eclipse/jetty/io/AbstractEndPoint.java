package org.eclipse.jetty.io;

import java.io.IOException;
import java.net.InetSocketAddress;

public abstract class AbstractEndPoint implements EndPoint
{
    private final long _created=System.currentTimeMillis();
    private final InetSocketAddress _local;
    private final InetSocketAddress _remote;
    private volatile int _maxIdleTime;    
    private volatile long _idleTimestamp;

    
    protected AbstractEndPoint(InetSocketAddress local,InetSocketAddress remote)
    {
        _local=local;
        _remote=remote;
    }
    
    @Override
    public long getCreatedTimeStamp()
    {
        return _created;
    }

    
    @Override
    public int getMaxIdleTime()
    {
        return _maxIdleTime;
    }

    @Override
    public void setMaxIdleTime(int timeMs) throws IOException
    {
        _maxIdleTime=timeMs;
    }

    /* ------------------------------------------------------------ */
    @Override
    public InetSocketAddress getLocalAddress()
    {
        return _local;
    }

    /* ------------------------------------------------------------ */
    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return _remote;
    }

    /* ------------------------------------------------------------ */
    public long getIdleTimestamp()
    {
        return _idleTimestamp;
    }

    /* ------------------------------------------------------------ */
    protected void notIdle()
    {
        _idleTimestamp=System.currentTimeMillis();
    }
    
    /* ------------------------------------------------------------ */
    public void onClose()
    {
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        return String.format("%s@%x{%s<r-l>%s,o=%b,os=%b}",
                getClass().getSimpleName(),
                hashCode(),
                getRemoteAddress(),
                getLocalAddress(),
                isOpen(),
                isOutputShutdown());
    }
    
}
