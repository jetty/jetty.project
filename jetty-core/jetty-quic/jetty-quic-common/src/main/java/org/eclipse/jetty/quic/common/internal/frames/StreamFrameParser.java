//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.common.internal.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.quic.util.QuicException;
import org.eclipse.jetty.quic.util.VarLenInt;

public class StreamFrameParser implements FrameParser
{
    private final VarLenInt varLenInt;
    private State state = State.FRAME_TYPE;
    private long maxFrameSize;
    private int frameSize;
    private long frameType;
    private boolean hasOffset;
    private boolean hasLength;
    private long streamId;
    private long offset = -1;
    private long dataLength = -1;

    public StreamFrameParser(VarLenInt varLenInt)
    {
        this.varLenInt = varLenInt;
    }

    public long getStreamFrameLength()
    {
        return maxFrameSize;
    }

    public void setStreamFrameLength(long maxFrameSize)
    {
        this.maxFrameSize = maxFrameSize;
    }

    @Override
    public StreamFrame parse(RetainableByteBuffer buffer)
    {
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        while (byteBuffer.hasRemaining())
        {
            switch (state)
            {
                case FRAME_TYPE ->
                {
                    if (varLenInt.tryDecode(byteBuffer, (l, v) ->
                    {
                        frameSize += l;
                        frameType = v;
                    }))
                    {
                        hasOffset = (frameType & StreamFrame.OFFSET_MASK) == StreamFrame.OFFSET_MASK;
                        hasLength = (frameType & StreamFrame.LENGTH_MASK) == StreamFrame.LENGTH_MASK;
                        state = State.STREAM_ID;
                    }
                }
                case STREAM_ID ->
                {
                    if (varLenInt.tryDecode(byteBuffer, (l, v) ->
                    {
                        frameSize += l;
                        streamId = v;
                    }))
                    {
                        if (hasOffset)
                            state = State.OFFSET;
                        else if (hasLength)
                            state = State.LENGTH;
                        else
                            state = State.DATA;
                    }
                }
                case OFFSET ->
                {
                    if (varLenInt.tryDecode(byteBuffer, (l, v) ->
                    {
                        frameSize += l;
                        offset = v;
                    }))
                    {
                        if (hasLength)
                            state = State.LENGTH;
                        else
                            state = State.DATA;
                    }
                }
                case LENGTH ->
                {
                    if (varLenInt.tryDecode(byteBuffer, (l, v) ->
                    {
                        frameSize += l;
                        dataLength = v;
                    }))
                    {
                        if (dataLength == 0)
                            return result(RetainableByteBuffer.EMPTY, true);
                        state = State.DATA;
                    }
                }
                case DATA ->
                {
                    long streamFrameLength = getStreamFrameLength();
                    // RFC-9000[19.8]: if no length, the frame data extends to the packet payload.
                    if (dataLength < 0)
                    {
                        if (streamFrameLength <= 0)
                            throw new QuicException(ErrorCode.INTERNAL_ERROR, "unexpected_frame_size", frameType);
                        dataLength = streamFrameLength - frameSize;
                    }

                    if (streamFrameLength > 0 && dataLength + frameSize > streamFrameLength)
                        throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_frame_size", frameType);

                    int length = (int)Math.min(dataLength, byteBuffer.remaining());
                    RetainableByteBuffer data = buffer.slice(length);
                    byteBuffer.position(byteBuffer.position() + length);
                    dataLength -= length;
                    boolean done = dataLength == 0;
                    return result(data, done);
                }
            }
        }
        return null;
    }

    private StreamFrame result(RetainableByteBuffer data, boolean complete)
    {
        long type = frameType;
        long off = offset;

        if (!complete)
        {
            // The frame is not complete, generate a synthetic frame.

            // Update the offset and set the offset bit.
            if (off < 0)
                off = 0;
            type |= StreamFrame.OFFSET_MASK;

            // Clear the endStream bit.
            boolean endStream = (type & StreamFrame.END_STREAM_MASK) == StreamFrame.END_STREAM_MASK;
            if (endStream)
                type &= ~StreamFrame.END_STREAM_MASK;

            // Update the offset for the next chunk
            // of data, and remain in DATA state.
            offset += data.remaining();
        }

        StreamFrame frame = new StreamFrame(type, streamId, data, off, complete);

        if (complete)
            reset();

        return frame;
    }

    private void reset()
    {
        state = State.FRAME_TYPE;
        frameType = 0;
        frameSize = 0;
        hasOffset = false;
        hasLength = false;
        streamId = 0;
        offset = -1;
        dataLength = -1;
    }

    private enum State
    {
        FRAME_TYPE, STREAM_ID, OFFSET, LENGTH, DATA
    }
}
