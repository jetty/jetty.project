package org.eclipse.jetty.spdy.nio;

import java.io.IOException;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.AsyncEndPoint;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.nio.AsyncConnection;

public class NoProtocolConnection extends AbstractConnection implements AsyncConnection
{
    public NoProtocolConnection(AsyncEndPoint endPoint)
    {
        super(endPoint);
    }

    public Connection handle() throws IOException
    {
        return this;
    }

    @Override
    public AsyncEndPoint getEndPoint()
    {
        return (AsyncEndPoint)super.getEndPoint();
    }

    @Override
    public boolean isIdle()
    {
        return false;
    }

    @Override
    public boolean isSuspended()
    {
        return false;
    }

    @Override
    public void onClose()
    {
        // TODO
    }

    @Override
    public void onInputShutdown() throws IOException
    {
        // TODO
    }
}
