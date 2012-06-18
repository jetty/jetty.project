package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.websocket.api.OpCode;
import org.eclipse.jetty.websocket.frames.ControlFrame;
import org.eclipse.jetty.websocket.frames.PingFrame;

public class PingFrameGenerator extends ControlFrameGenerator
{
    public PingFrameGenerator(ByteBufferPool bufferPool)
    {
        super(bufferPool);
    }

    @Override
    public ByteBuffer generate(ControlFrame frame)
    {
        PingFrame ping = (PingFrame)frame;

        ByteBuffer buffer = super.generate(frame);

        buffer.putInt(OpCode.PING.getCode());
        buffer.put(frame.getMask());
        buffer.putLong(frame.getPayloadLength());

        buffer.put(ping.getPayload().array());
        return buffer;
    }

}
