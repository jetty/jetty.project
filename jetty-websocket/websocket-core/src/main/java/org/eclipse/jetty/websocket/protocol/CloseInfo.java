package org.eclipse.jetty.websocket.protocol;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.api.StatusCode;

public class CloseInfo
{
    private static final Logger LOG = Log.getLogger(CloseInfo.class);
    private int statusCode;
    private String reason;

    public CloseInfo(byte payload[], boolean validate)
    {
        this.statusCode = 0;
        this.reason = null;

        if ((payload == null) || (payload.length == 0))
        {
            return; // nothing to do
        }

        if ((payload.length == 1) && (validate))
        {
            throw new ProtocolException("Invalid 1 byte payload");
        }

        if (payload.length >= 2)
        {
            // Status Code
            ByteBuffer bb = ByteBuffer.wrap(payload);
            statusCode |= (bb.get(0) & 0xFF) << 8;
            statusCode |= (bb.get(1) & 0xFF);

            if(validate) {
                if ((statusCode < StatusCode.NORMAL) || (statusCode == StatusCode.UNDEFINED) || (statusCode == StatusCode.NO_CLOSE)
                        || (statusCode == StatusCode.NO_CODE) || ((statusCode > 1011) && (statusCode <= 2999)) || (statusCode >= 5000))
                {
                    throw new ProtocolException("Invalid close code: " + statusCode);
                }
            }

            if (payload.length > 2)
            {
                // Reason
                try
                {
                    reason = StringUtil.toUTF8String(payload,2,payload.length - 2);
                }
                catch (RuntimeException e)
                {
                    if (validate)
                    {
                        throw new ProtocolException("Invalid Close Reason:",e);
                    }
                    else
                    {
                        LOG.warn(e);
                    }
                }
            }
        }
    }

    public CloseInfo(WebSocketFrame frame)
    {
        this(frame.getPayloadData(),false);
    }

    public String getReason()
    {
        return reason;
    }

    public int getStatusCode()
    {
        return statusCode;
    }
}
