package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.WebSocketSettings;
import org.eclipse.jetty.websocket.frames.PongFrame;

public class PongPayloadParser extends FrameParser<PongFrame>
{
    private PongFrame frame;

    public PongPayloadParser(WebSocketSettings settings)
    {
        super(settings);
        frame = new PongFrame();
    }

    @Override
    public PongFrame getFrame()
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
