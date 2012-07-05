package org.eclipse.jetty.websocket.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.protocol.OpCode;

public abstract class ControlFrame extends BaseFrame
{
    public static final int MAX_PAYLOAD = 125;

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

    @Override
    public void setPayload(byte[] buf)
    {
        if (buf.length > MAX_PAYLOAD)
        {
            throw new ProtocolException("Control Payloads can not exceed 125 bytes in length.");
        }

        super.setPayload(buf);
    }

    @Override
    public void setPayload(ByteBuffer payload)
    {
        if (payload.position() > MAX_PAYLOAD)
        {
            throw new ProtocolException("Control Payloads can not exceed 125 bytes in length.");
        }

        super.setPayload(payload);
    }

    @Override
    public void setRsv1(boolean rsv1)
    {
        if (rsv1)
        {
            throw new IllegalArgumentException("Cannot set RSV1 to true on a " + getOpCode().name());
        }
    }

    @Override
    public void setRsv2(boolean rsv2)
    {
        if (rsv2)
        {
            throw new IllegalArgumentException("Cannot set RSV2 to true on a " + getOpCode().name());
        }
    }

    @Override
    public void setRsv3(boolean rsv3)
    {
        if (rsv3)
        {
            throw new IllegalArgumentException("Cannot set RSV3 to true on a " + getOpCode().name());
        }
    }
}
