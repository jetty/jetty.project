package org.eclipse.jetty.websocket.frames;

import org.eclipse.jetty.websocket.api.OpCode;

public class ContinuationFrame extends BaseFrame
{
    public ContinuationFrame()
    {
        super(OpCode.CONTINUATION);
    }
}
