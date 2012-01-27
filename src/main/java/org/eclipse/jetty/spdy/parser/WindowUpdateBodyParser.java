package org.eclipse.jetty.spdy.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.frames.WindowUpdateFrame;

public class WindowUpdateBodyParser extends ControlFrameBodyParser
{
    private final ControlFrameParser controlFrameParser;
    private State state = State.STREAM_ID;
    private int cursor;
    private int streamId;
    private int windowDelta;

    public WindowUpdateBodyParser(ControlFrameParser controlFrameParser)
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
                case STREAM_ID:
                {
                    if (buffer.remaining() >= 4)
                    {
                        streamId = buffer.getInt() & 0x7F_FF_FF_FF;
                        state = State.WINDOW_DELTA;
                    }
                    else
                    {
                        state = State.STREAM_ID_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case STREAM_ID_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    streamId += (currByte & 0xFF) << 8 * cursor;
                    if (cursor == 0)
                    {
                        streamId &= 0x7F_FF_FF_FF;
                        state = State.WINDOW_DELTA;
                    }
                    break;
                }
                case WINDOW_DELTA:
                {
                    if (buffer.remaining() >= 4)
                    {
                        windowDelta = buffer.getInt() & 0x7F_FF_FF_FF;
                        onWindowUpdate();
                        return true;
                    }
                    else
                    {
                        state = State.WINDOW_DELTA_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case WINDOW_DELTA_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    windowDelta += (currByte & 0xFF) << 8 * cursor;
                    if (cursor == 0)
                    {
                        windowDelta &= 0x7F_FF_FF_FF;
                        onWindowUpdate();
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

    private void onWindowUpdate()
    {
        WindowUpdateFrame frame = new WindowUpdateFrame(controlFrameParser.getVersion(), streamId, windowDelta);
        controlFrameParser.onControlFrame(frame);
        reset();
    }

    private void reset()
    {
        state = State.STREAM_ID;
        cursor = 0;
        streamId = 0;
        windowDelta = 0;
    }

    private enum State
    {
        STREAM_ID, STREAM_ID_BYTES, WINDOW_DELTA, WINDOW_DELTA_BYTES;
    }
}
