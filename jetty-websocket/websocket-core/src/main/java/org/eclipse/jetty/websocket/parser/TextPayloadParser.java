package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.frames.TextFrame;

public class TextPayloadParser extends FrameParser<TextFrame>
{
    private TextFrame frame;

    public TextPayloadParser()
    {
        super();
        frame = new TextFrame();
    }

    @Override
    public TextFrame getFrame()
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
