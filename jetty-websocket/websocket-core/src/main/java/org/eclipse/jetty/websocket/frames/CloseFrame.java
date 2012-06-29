package org.eclipse.jetty.websocket.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.OpCode;
import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketBehavior;

/**
 * Representation of a <a href="https://tools.ietf.org/html/rfc6455#section-5.5.1">Close Frame (0x08)</a>.
 */
public class CloseFrame extends ControlFrame
{
    public static final int MAX_REASON = ControlFrame.MAX_PAYLOAD - 2;

    /**
     * Construct CloseFrame with no status code or reason
     */
    public CloseFrame()
    {
        super(OpCode.CLOSE);
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

    public void assertValidPayload(int statusCode, String reason)
    {
        if ((statusCode < StatusCode.NORMAL) || (statusCode >= 5000))
        {
            throw new ProtocolException("Status Codes must be in the range 1000 - 5000");
        }

        if ((reason != null) && (reason.length() > 123))
        {
            throw new ProtocolException("Reason must not exceed 123 characters.");
        }

        // TODO add check for invalid utf-8
    }

    public void assertValidPerPolicy(WebSocketBehavior behavior)
    {
        int code = getStatusCode();
        if ((code < StatusCode.NORMAL) || (code == StatusCode.UNDEFINED) || (code == StatusCode.NO_CLOSE) || (code == StatusCode.NO_CODE)
                || ((code > 1011) && (code <= 2999)) || (code >= 5000))
        {
            throw new ProtocolException("Invalid close code: " + code);
        }
    }

    private void constructPayload(int statusCode, String reason)
    {
        assertValidPayload(statusCode,reason);

        byte utf[] = null;
        int len = 2; // status code
        if (StringUtil.isNotBlank(reason))
        {
            utf = reason.getBytes(StringUtil.__UTF8_CHARSET);
            len += utf.length;
        }

        ByteBuffer payload = ByteBuffer.allocate(len);
        payload.putChar((char)statusCode);
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
    public void setPayload(byte[] buf)
    {
        super.setPayload(buf);
        assertValidPayload(getStatusCode(),getReason());
    }

    @Override
    public void setPayload(ByteBuffer payload)
    {
        super.setPayload(payload);
        assertValidPayload(getStatusCode(),getReason());
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
