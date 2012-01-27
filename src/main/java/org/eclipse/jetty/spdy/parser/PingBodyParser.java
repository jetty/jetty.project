package org.eclipse.jetty.spdy.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.frames.PingFrame;

public class PingBodyParser extends ControlFrameBodyParser
{
    private final ControlFrameParser controlFrameParser;
    private State state = State.PING_ID;
    private int cursor;
    private int pingId;

    public PingBodyParser(ControlFrameParser controlFrameParser)
    {
        this.controlFrameParser = controlFrameParser;
    }

    @Override
    public boolean parse(ByteBuffer buffer) throws StreamException
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case PING_ID:
                {
                    if (buffer.remaining() >= 4)
                    {
                        pingId = buffer.getInt() & 0x7F_FF_FF_FF;
                        onPing();
                        return true;
                    }
                    else
                    {
                        state = State.PING_ID_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case PING_ID_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    pingId += (currByte & 0xFF) << 8 * cursor;
                    if (cursor == 0)
                    {
                        onPing();
                        return true;
                    }
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        return false;
    }

    private void onPing()
    {
        PingFrame frame = new PingFrame(controlFrameParser.getVersion(), pingId);
        controlFrameParser.onControlFrame(frame);
        reset();
    }

    private void reset()
    {
        state = State.PING_ID;
        cursor = 0;
        pingId = 0;
    }

    private enum State
    {
        PING_ID, PING_ID_BYTES
    }
}
