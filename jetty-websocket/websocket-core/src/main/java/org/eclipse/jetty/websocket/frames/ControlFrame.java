package org.eclipse.jetty.websocket.frames;

import org.eclipse.jetty.websocket.api.OpCode;

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
    public void setFin(boolean fin)
    {
        if (!fin)
        {
            throw new IllegalArgumentException("Cannot set FIN to off on a " + getOpCode().name());
        }
    }
}
