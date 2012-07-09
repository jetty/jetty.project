package org.eclipse.jetty.websocket.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.InterruptedByTimeoutException;
import java.util.concurrent.ScheduledFuture;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

public abstract class FrameBytes<C> implements Callback<C>, Runnable
{
    protected final RawConnection connection;
    protected final Callback<C> callback;
    protected final C context;
    protected final WebSocketFrame frame;
    // Task used to timeout the bytes
    protected volatile ScheduledFuture<?> task;

    protected FrameBytes(RawConnection connection, Callback<C> callback, C context, WebSocketFrame frame)
    {
        this.connection = connection;
        this.callback = callback;
        this.context = context;
        this.frame = frame;
    }

    private void cancelTask()
    {
        ScheduledFuture<?> task = this.task;
        if (task != null)
        {
            task.cancel(false);
        }
    }

    @Override
    public void completed(C context)
    {
        cancelTask();
        callback.completed(context);
    }

    @Override
    public void failed(C context, Throwable x)
    {
        cancelTask();
        callback.failed(context,x);
    }

    public abstract ByteBuffer getByteBuffer();

    @Override
    public void run()
    {
        // If this occurs we had a timeout!
        try
        {
            connection.close();
        }
        catch (IOException e)
        {
            WebSocketAsyncConnection.LOG.ignore(e);
        }
        failed(context, new InterruptedByTimeoutException());
    }

    @Override
    public String toString()
    {
        return frame.toString();
    }
}