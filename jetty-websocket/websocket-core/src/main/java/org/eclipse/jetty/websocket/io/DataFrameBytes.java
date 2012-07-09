package org.eclipse.jetty.websocket.io;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

public class DataFrameBytes<C> extends FrameBytes<C>
{
    private int size;
    private ByteBuffer buffer;

    public DataFrameBytes(RawConnection connection, Callback<C> callback, C context, WebSocketFrame frame)
    {
        super(connection,callback,context,frame);
    }

    @Override
    public void completed(C context)
    {
        connection.getBufferPool().release(buffer);

        if (frame.remaining() > 0)
        {
            // We have written a frame out of this DataInfo, but there is more to write.
            // We need to keep the correct ordering of frames, to avoid that another
            // DataInfo for the same stream is written before this one is finished.
            connection.getQueue().prepend(this);
        }
        else
        {
            super.completed(context);
        }
    }

    @Override
    public ByteBuffer getByteBuffer()
    {
        try
        {
            int windowSize = connection.getPolicy().getBufferSize();
            // TODO: create a window size?

            size = frame.getPayloadLength();
            if (size > windowSize)
            {
                size = windowSize;
            }

            buffer = connection.getGenerator().generate(size,frame);
            return buffer;
        }
        catch (Throwable x)
        {
            failed(context,x);
            return null;
        }
    }
}