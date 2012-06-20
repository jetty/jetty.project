package org.eclipse.jetty.websocket.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.OpCode;

public abstract class ControlFrame extends BaseFrame
{
    private ByteBuffer payload = null; // TODO decide if payload needs to go all the way down to baseframe

    public ControlFrame()
    {
        super();
    }

    public ControlFrame(OpCode opcode)
    {
        super(opcode);
    }

    public ByteBuffer getPayload()
    {
        return payload;
    }

    public boolean hasPayload()
    {
        return payload != null;
    }

    public void setPayload(ByteBuffer payload)
    {
        this.payload = payload;
        setPayloadLength(this.payload.position());
    }
}
