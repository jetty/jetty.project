package org.eclipse.jetty.spdy.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.frames.RstStreamFrame;

public class RstStreamBodyParser extends ControlFrameBodyParser
{
    private final ControlFrameParser controlFrameParser;
    private State state = State.STREAM_ID;
    private int cursor;
    private int streamId;
    private int statusCode;

    public RstStreamBodyParser(ControlFrameParser controlFrameParser)
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
                        state = State.STATUS_CODE;
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
                        state = State.STATUS_CODE;
                    }
                    break;
                }
                case STATUS_CODE:
                {
                    if (buffer.remaining() >= 4)
                    {
                        statusCode = buffer.getInt();
                        onRstStream();
                        return true;
                    }
                    else
                    {
                        state = State.STATUS_CODE_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case STATUS_CODE_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    statusCode += (currByte & 0xFF) << 8 * cursor;
                    if (cursor == 0)
                    {
                        onRstStream();
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

    private void onRstStream()
    {
        // TODO: check that statusCode is not 0
        RstStreamFrame frame = new RstStreamFrame(controlFrameParser.getVersion(), streamId, statusCode);
        controlFrameParser.onControlFrame(frame);
        reset();
    }

    private void reset()
    {
        state = State.STREAM_ID;
        cursor = 0;
        streamId = 0;
        statusCode = 0;
    }

    private enum State
    {
        STREAM_ID, STREAM_ID_BYTES, STATUS_CODE, STATUS_CODE_BYTES
    }
}
