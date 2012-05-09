package org.eclipse.jetty.io;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class AsyncByteArrayEndPoint extends ByteArrayEndPoint implements AsyncEndPoint
{
    public static final Logger LOG=Log.getLogger(AsyncByteArrayEndPoint.class);

    @Override
    public <C> void readable(C context, Callback<C> callback) throws IllegalStateException
    {
        // TODO Auto-generated method stub
        
    }

    @Override
    public <C> void write(C context, Callback<C> callback, ByteBuffer... buffers) throws IllegalStateException
    {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void setCheckForIdle(boolean check)
    {
        // TODO Auto-generated method stub
        
    }

    @Override
    public boolean isCheckForIdle()
    {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public AsyncConnection getAsyncConnection()
    {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void setAsyncConnection(AsyncConnection connection)
    {
        // TODO Auto-generated method stub
        
    }


}
