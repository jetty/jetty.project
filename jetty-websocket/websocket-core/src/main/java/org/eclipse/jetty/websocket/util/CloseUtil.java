package org.eclipse.jetty.websocket.util;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.ProtocolException;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

public class CloseUtil
{
    public static void assertValidPayload(WebSocketFrame frame)
    {
        byte payload[] = frame.getPayloadData();
        int statusCode = getStatusCode(payload);

        // Validate value
        if ((statusCode < StatusCode.NORMAL) || (statusCode == StatusCode.UNDEFINED) || (statusCode == StatusCode.NO_CLOSE)
                || (statusCode == StatusCode.NO_CODE) || ((statusCode > 1011) && (statusCode <= 2999)) || (statusCode >= 5000))
        {
            throw new ProtocolException("Invalid close code: " + statusCode);
        }

        // validate reason
        getReason(payload);
    }

    public static String getReason(byte[] payload)
    {
        if (payload.length <= 2)
        {
            return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(payload);
        int len = payload.length - 2;
        byte utf[] = new byte[len];
        for (int i = 0; i < len; i++)
        {
            utf[i] = bb.get(i + 2);
        }
        return StringUtil.toUTF8String(utf,0,utf.length);
    }

    public static int getStatusCode(byte[] payload)
    {
        if (payload.length < 2)
        {
            return 0; // no status code
        }

        int statusCode = 0;
        ByteBuffer bb = ByteBuffer.wrap(payload);
        statusCode |= (bb.get(0) & 0xFF) << 8;
        statusCode |= (bb.get(1) & 0xFF);
        return statusCode;
    }
}
