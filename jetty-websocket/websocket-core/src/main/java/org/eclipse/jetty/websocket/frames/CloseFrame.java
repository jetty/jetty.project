package org.eclipse.jetty.websocket.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.OpCode;

/**
 * Representation of a <a href="https://tools.ietf.org/html/rfc6455#section-5.5.1">Close Frame (0x08)</a>.
 */
public class CloseFrame extends ControlFrame
{
    /**
     * Construct CloseFrame with no status code or reason
     */
    public CloseFrame()
    {
        super(OpCode.CLOSE);
        // no status code, no reason
        setPayload(BufferUtil.EMPTY_BUFFER);
    }

    /**
     * Construct CloseFrame with status code and no reason
     */
    public CloseFrame(int statusCode)
    {
        super(OpCode.CLOSE);
        constructPayload(statusCode,null);
    }

    /**
     * Construct CloseFrame with status code and reason
     */
    public CloseFrame(int statusCode, String reason)
    {
        super(OpCode.CLOSE);
        constructPayload(statusCode,reason);
    }

    private void constructPayload(int statusCode, String reason)
    {
        if ((statusCode <= 999) || (statusCode > 65535))
        {
            throw new IllegalArgumentException("Status Codes must be in the range 1000 - 65535");
        }

        byte utf[] = null;
        int len = 2; // status code
        if (StringUtil.isNotBlank(reason))
        {
            utf = reason.getBytes(StringUtil.__UTF8_CHARSET);
            len += utf.length;
        }

        ByteBuffer payload = ByteBuffer.allocate(len);
        payload.putShort((short)statusCode);
        if (utf != null)
        {
            payload.put(utf,0,utf.length);
        }
        setPayload(payload);
    }

    public String getReason()
    {
        if (getPayloadLength() <= 2)
        {
            return null;
        }
        ByteBuffer payload = getPayload();
        int len = getPayloadLength() - 2;
        byte utf[] = new byte[len];
        for (int i = 0; i < len; i++)
        {
            utf[i] = payload.get(i + 2);
        }
        return StringUtil.toUTF8String(utf,0,utf.length);
    }

    public int getStatusCode()
    {
        if (getPayloadLength() < 2)
        {
            return 0; // no status code
        }

        int statusCode = 0;
        ByteBuffer payload = getPayload();
        statusCode |= (payload.get(0) & 0xFF) << 8;
        statusCode |= (payload.get(1) & 0xFF);
        return statusCode;
    }

    public boolean hasReason()
    {
        return getPayloadLength() > 2;
    }

    @Override
    public String toString()
    {
        StringBuilder b = new StringBuilder();
        b.append("CloseFrame[");
        b.append("len=").append(getPayloadLength());
        b.append(",statusCode=").append(getStatusCode());
        b.append(",reason=").append(getReason());
        b.append("]");
        return b.toString();
    }
}
