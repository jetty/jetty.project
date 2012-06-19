package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.frames.ContinuationFrame;

/**
 * Parsing for the {@link ContinuationFrame}.
 */
public class ContinuationPayloadParser extends FrameParser<ContinuationFrame>
{
    private ContinuationFrame frame;

    public ContinuationPayloadParser()
    {
        super();
        frame = new ContinuationFrame();
    }

    @Override
    public ContinuationFrame getFrame()
    {
        return frame;
    }

    @Override
    public boolean parsePayload(ByteBuffer buf)
    {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public void reset()
    {
        super.reset();
    }
}
