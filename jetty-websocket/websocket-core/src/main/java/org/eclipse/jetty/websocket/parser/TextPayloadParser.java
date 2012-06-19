package org.eclipse.jetty.websocket.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.WebSocketSettings;
import org.eclipse.jetty.websocket.frames.TextFrame;

public class TextPayloadParser extends FrameParser<TextFrame>
{
    private TextFrame frame;
    private ByteBuffer payload;
    private int payloadLength;

    public TextPayloadParser(WebSocketSettings settings)
    {
        super(settings);
        frame = new TextFrame();
    }

    @Override
    public TextFrame getFrame()
    {
        return frame;
    }

    @Override
    public boolean parsePayload(ByteBuffer buffer)
    {
        payloadLength = getFrame().getPayloadLength();
        while (buffer.hasRemaining())
        {
            if (payload == null)
            {
                payload = ByteBuffer.allocate(payloadLength);
            }

            copyBuffer(buffer,payload,payload.remaining());

            if (payload.position() >= payloadLength)
            {
                payload.flip();
                frame.setData(BufferUtil.toString(payload));
                return true;
            }
        }
        return false;
    }

    @Override
    public void reset()
    {
        super.reset();
        payloadLength = 0;
    }
}
