package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.frames.BinaryFrame;

/**
 * Parsing for the {@link BinaryFrame}.
 */
public class BinaryPayloadParser extends FrameParser<BinaryFrame>
{
    private BinaryFrame frame;

    public BinaryPayloadParser()
    {
        super();
        frame = new BinaryFrame();
    }

    @Override
    public BinaryFrame getFrame()
    {
        return frame;
    }

    @Override
    public boolean parsePayload(ByteBuffer buffer)
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
