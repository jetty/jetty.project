package org.eclipse.jetty.websocket.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.OpCode;
import org.eclipse.jetty.websocket.api.WebSocketException;

public abstract class ControlFrame extends BaseFrame
{
    private ByteBuffer payload = null; // TODO decide if payload needs to go all the way down to baseframe
    
    public ControlFrame()
    {
        super();
    }

    public ControlFrame(BaseFrame copy)
    {
        super(copy);
    }

    public ControlFrame(OpCode opcode)
    {
        super(opcode);
    }
    
    public ByteBuffer getPayload()
    {
        return payload;
    }

    public void setPayload(ByteBuffer payload)
    {
        if ( payload.array().length >= 126 )
        {
            this.payload = payload;
            setPayloadLength(payload.array().length);
        }
        else
        {
            throw new WebSocketException("too long, catch this better");
        }
    }

    public boolean hasPayload()
    {
        return payload != null;
    }
}
