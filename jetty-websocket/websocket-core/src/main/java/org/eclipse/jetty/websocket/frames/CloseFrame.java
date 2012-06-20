package org.eclipse.jetty.websocket.frames;

import org.eclipse.jetty.websocket.api.OpCode;
import org.eclipse.jetty.websocket.api.WebSocket;

/**
 * Representation of a <a href="https://tools.ietf.org/html/rfc6455#section-5.5.1">Close Frame (0x08)</a>.
 */
public class CloseFrame extends ControlFrame
{
    private final short statusCode;
    private String reason;

    public CloseFrame()
    {
        this(WebSocket.CLOSE_NORMAL); // TODO: evaluate default (or unspecified status code)
    }

    public CloseFrame(short statusCode)
    {
        super(OpCode.CLOSE);
        this.statusCode = statusCode;
    }

    @Override
    public OpCode getOpCode()
    {
        return OpCode.CLOSE;
    }

    public String getReason()
    {
        return reason;
    }

    public short getStatusCode()
    {
        return statusCode;
    }

    public void setReason(String reason)
    {
        this.reason = reason;
    }

    @Override
    public String toString()
    {
        StringBuilder b = new StringBuilder();
        b.append("CloseFrame[");
        b.append("len=").append(getPayloadLength());
        b.append(",statusCode=").append(statusCode);
        b.append(",reason=").append(reason);
        b.append("]");
        return b.toString();
    }
}
