package org.eclipse.jetty.websocket.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.frames.CloseFrame;

public class CloseFrameGenerator extends FrameGenerator<CloseFrame>
{
    public CloseFrameGenerator(WebSocketPolicy policy)
    {
        super(policy);
    }

    @Override
    public void fillPayload(ByteBuffer buffer, CloseFrame close)
    {
        if ( close.getStatusCode() != 0 )
        {
            buffer.putChar((char)close.getStatusCode()); // char is unsigned 16

            // payload requires a status code in order to be written
            if ( close.hasPayload() )
            {
                if (close.hasReason())
                {
                    byte utf[] = close.getReason().getBytes(StringUtil.__UTF8_CHARSET);
                    buffer.put(utf,0,utf.length);
                }
            }
        }
        else if (close.hasPayload())
        {
            throw new WebSocketException("Close frames require setting a status code if using payload.");
        }
    }
}
