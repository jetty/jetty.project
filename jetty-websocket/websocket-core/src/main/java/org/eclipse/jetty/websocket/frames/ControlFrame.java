package org.eclipse.jetty.websocket.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.OpCode;
import org.eclipse.jetty.websocket.api.WebSocketException;

public abstract class ControlFrame extends BaseFrame
{
    public ControlFrame()
    {
        super();
        super.setFin(true);
    }

    public ControlFrame(OpCode opcode)
    {
        super(opcode);
        super.setFin(true);
    }

    @Override
    public boolean isContinuation()
    {
        return false; // no control frames can be continuation
    }
    
    @Override
    public void setPayload(ByteBuffer payload)
    {
        if ( payload.position() > 125 )
        {
            throw new WebSocketException("Control Payloads can not exceed 125 bytes in length.");
        }
        
        super.setPayload(payload);
    }

    @Override
    protected void setPayload(byte[] buf)
    {
        if ( buf.length > 125 )
        {
            throw new WebSocketException("Control Payloads can not exceed 125 bytes in length.");
        }
        
        super.setPayload(buf);      
    }
    
    @Override
    public void setFin(boolean fin)
    {
        if (!fin)
        {
            throw new IllegalArgumentException("Cannot set FIN to off on a " + getOpCode().name());
        }
    }
}
