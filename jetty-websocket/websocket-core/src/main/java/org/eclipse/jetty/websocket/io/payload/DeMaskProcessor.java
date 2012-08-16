package org.eclipse.jetty.websocket.io.payload;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

public class DeMaskProcessor implements PayloadProcessor
{
    private boolean isMasked;
    private byte mask[];
    private int offset;

    @Override
    public void process(ByteBuffer payload)
    {
        if (!isMasked)
        {
            return;
        }

        int start = payload.position();
        int end = payload.limit();
        for (int i = start; i < end; i++, offset++)
        {
            payload.put(i,(byte)(payload.get(i) ^ mask[offset % 4]));
        }
    }

    @Override
    public void reset(WebSocketFrame frame)
    {
        this.isMasked = frame.isMasked();
        if (isMasked)
        {
            this.mask = frame.getMask();
        }
        else
        {
            this.mask = null;
        }

        offset = 0;
    }
}
