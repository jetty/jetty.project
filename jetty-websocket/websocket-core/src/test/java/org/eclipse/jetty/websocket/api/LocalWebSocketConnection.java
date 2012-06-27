package org.eclipse.jetty.websocket.api;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.frames.BaseFrame;

public class LocalWebSocketConnection implements WebSocketConnection
{
    @Override
    public void close()
    {
        // TODO Auto-generated method stub
    }

    @Override
    public void close(int statusCode, String reason)
    {
        // TODO Auto-generated method stub

    }

    @Override
    public WebSocketPolicy getPolicy()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public InetAddress getRemoteAddress()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public String getSubProtocol()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean isOpen()
    {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void write(BaseFrame frame) throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void write(byte[] data, int offset, int length) throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void write(ByteBuffer... buffers) throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public <C> void write(C context, Callback<C> callback, BaseFrame... frames) throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public <C> void write(C context, Callback<C> callback, ByteBuffer... buffers) throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public <C> void write(C context, Callback<C> callback, String... messages) throws IOException
    {
        // TODO Auto-generated method stub

    }

    @Override
    public void write(String message) throws IOException
    {
        // TODO Auto-generated method stub

    }

}
