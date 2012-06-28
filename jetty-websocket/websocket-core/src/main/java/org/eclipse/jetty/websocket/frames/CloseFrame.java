package org.eclipse.jetty.websocket.frames;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.OpCode;
import org.eclipse.jetty.websocket.api.StatusCode;

/**
 * Representation of a <a href="https://tools.ietf.org/html/rfc6455#section-5.5.1">Close Frame (0x08)</a>.
 */
public class CloseFrame extends ControlFrame
{
    private int statusCode = 0;
    private String reason = "";

    public CloseFrame()
    {
        super(OpCode.CLOSE);
    }

    public CloseFrame(int statusCode)
    {
        super(OpCode.CLOSE);
        setStatusCode(statusCode);
    }

    public String getReason()
    {
        return reason;
    }

    public int getStatusCode()
    {
        return statusCode;
    }

    public boolean hasReason()
    {
        return StringUtil.isBlank(reason);
    }

    public void setReason(String reason)
    {
        this.reason = reason;
    }

    public void setStatusCode(int statusCode)
    {
        if ( ( statusCode <= 999) || ( statusCode > 65535 ) )
        {
            throw new IllegalArgumentException("Status Codes must be in the range 1000 - 65535");
        }
        
        this.statusCode = statusCode;
    }

    @Override
    public int getPayloadLength()
    {
        /*
         * issue here is that the parser can set the payload length and then rely on it when parsing payload
         * 
         * but generator doesn't have a payload length without calculating it via the statuscode and reason
         * 
         */
        if (super.getPayloadLength() == 0)
        {
            int length = 0;
            if (getStatusCode() != 0)
            {
                length = length + 2;

                if (hasReason())
                {
                    length = length + getReason().getBytes().length;
                }
            }

            return length;
        }
        else
        {
            return super.getPayloadLength();
        }
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
