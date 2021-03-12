package org.eclipse.jetty.http3.server;

import java.util.EventListener;

import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;

public class QuicConnection implements Connection
{
    @Override
    public void addEventListener(EventListener listener)
    {

    }

    @Override
    public void removeEventListener(EventListener listener)
    {

    }

    @Override
    public void onOpen()
    {

    }

    @Override
    public void onClose(Throwable cause)
    {

    }

    @Override
    public EndPoint getEndPoint()
    {
        return null;
    }

    @Override
    public void close()
    {

    }

    @Override
    public boolean onIdleExpired()
    {
        return false;
    }

    @Override
    public long getMessagesIn()
    {
        return 0;
    }

    @Override
    public long getMessagesOut()
    {
        return 0;
    }

    @Override
    public long getBytesIn()
    {
        return 0;
    }

    @Override
    public long getBytesOut()
    {
        return 0;
    }

    @Override
    public long getCreatedTimeStamp()
    {
        return 0;
    }
}
