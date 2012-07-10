package org.eclipse.jetty.websocket.io;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.generator.Generator;
import org.eclipse.jetty.websocket.protocol.OpCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

public class ControlFrameBytes<C> extends FrameBytes<C>
{
    private ByteBuffer buffer;

    public ControlFrameBytes(RawConnection connection, Callback<C> callback, C context, WebSocketFrame frame)
    {
        super(connection,callback,context,frame);
    }

    @Override
    public void completed(C context) {
        connection.getBufferPool().release(buffer);

        super.completed(context);

        if(frame.getOpCode() == OpCode.CLOSE)
        {
            // TODO: close the connection (no packet)
        }
    }

    @Override
    public ByteBuffer getByteBuffer()
    {
        if (buffer == null)
        {
            buffer = connection.getBufferPool().acquire(frame.getPayloadLength() + Generator.OVERHEAD,false);
            connection.getGenerator().generate(frame);
            BufferUtil.flipToFlush(buffer,0);
        }
        return buffer;
    }
}