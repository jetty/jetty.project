package org.eclipse.jetty.websocket.protocol;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;

public class UnitParser extends Parser
{
    public UnitParser()
    {
        super(WebSocketPolicy.newServerPolicy());
    }

    private void parsePartial(ByteBuffer buf, int numBytes)
    {
        int len = Math.min(numBytes,buf.remaining());
        byte arr[] = new byte[len];
        buf.get(arr,0,len);
        this.parse(ByteBuffer.wrap(arr));
    }

    public void parseSlowly(ByteBuffer buf, int segmentSize)
    {
        while (buf.remaining() > 0)
        {
            parsePartial(buf,segmentSize);
        }
    }
}
