package org.eclipse.jetty.http2.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http2.frames.ResetFrame;

public class ResetBodyParser extends BodyParser
{
    private State state = State.ERROR;
    private int cursor;
    private int error;

    public ResetBodyParser(HeaderParser headerParser, Parser.Listener listener)
    {
        super(headerParser, listener);
    }

    @Override
    protected void reset()
    {
        super.reset();
        state = State.ERROR;
        cursor = 0;
        error = 0;
    }

    @Override
    public Result parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case ERROR:
                {
                    if (buffer.remaining() >= 4)
                    {
                        return onReset(buffer.getInt());
                    }
                    else
                    {
                        state = State.ERROR_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case ERROR_BYTES:
                {
                    int currByte = buffer.get() & 0xFF;
                    --cursor;
                    error += currByte << (8 * cursor);
                    if (cursor == 0)
                    {
                        return onReset(error);
                    }
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        return Result.PENDING;
    }

    private Result onReset(int error)
    {
        ResetFrame frame = new ResetFrame(getStreamId(), error);
        reset();
        return notifyResetFrame(frame) ? Result.ASYNC : Result.COMPLETE;
    }

    private enum State
    {
        ERROR, ERROR_BYTES
    }
}
