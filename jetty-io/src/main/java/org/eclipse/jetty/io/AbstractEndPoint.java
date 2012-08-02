package org.eclipse.jetty.io;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public abstract class AbstractEndPoint implements EndPoint
{
    private static final Logger LOG = Log.getLogger(AbstractEndPoint.class);
    private final long _created=System.currentTimeMillis();
    private final InetSocketAddress _local;
    private final InetSocketAddress _remote;
    private volatile long _idleTimeout;
    private volatile long _idleTimestamp=System.currentTimeMillis();
    private volatile Connection _connection;


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
        return _idleTimeout;
    }

    @Override
    public void setIdleTimeout(long idleTimeout)
    {
        _idleTimeout = idleTimeout;
    }

    @Override
    public InetSocketAddress getLocalAddress()
    {
        return _local;
    }

    @Override
    public InetSocketAddress getRemoteAddress()
    {
        return _remote;
    }

    public long getIdleTimestamp()
    {
        return _idleTimestamp;
    }

    protected void notIdle()
    {
        _idleTimestamp=System.currentTimeMillis();
    }

    @Override
    public Connection getConnection()
    {
        return _connection;
    }

    @Override
    public void setConnection(Connection connection)
    {
        _connection = connection;
    }

    @Override
    public void onOpen()
    {
        LOG.debug("onOpen {}",this);
    }

    @Override
    public void onClose()
    {
        LOG.debug("onClose {}",this);
    }

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
