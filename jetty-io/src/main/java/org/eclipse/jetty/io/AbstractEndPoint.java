package org.eclipse.jetty.io;

import java.net.InetSocketAddress;

public abstract class AbstractEndPoint implements EndPoint
{
    private final long _created=System.currentTimeMillis();
    private final InetSocketAddress _local;
    private final InetSocketAddress _remote;
    private volatile long _maxIdleTime;
    private volatile long _idleTimestamp=System.currentTimeMillis();


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
    public long getIdleTimeout()
    {
        return _maxIdleTime;
    }

    @Override
    public void setIdleTimeout(long timeMs)
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
