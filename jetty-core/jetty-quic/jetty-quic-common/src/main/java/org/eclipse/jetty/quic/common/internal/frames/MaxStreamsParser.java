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
import org.eclipse.jetty.quic.api.frames.MaxStreamsFrame;
import org.eclipse.jetty.quic.common.StreamId;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.quic.util.QuicException;
import org.eclipse.jetty.quic.util.VarLenInt;

public class MaxStreamsParser implements FrameParser
{
    private final VarLenInt varLenInt;
    private State state = State.FRAME_TYPE;
    private long frameType;
    private long maxStreams;

    public MaxStreamsParser(VarLenInt varLenInt)
    {
        this.varLenInt = varLenInt;
    }

    @Override
    public MaxStreamsFrame parse(RetainableByteBuffer buffer)
    {
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        while (byteBuffer.hasRemaining())
        {
            switch (state)
            {
                case FRAME_TYPE ->
                {
                    frameType = byteBuffer.get() & 0xFF;
                    state = State.MAX_STREAMS;
                }
                case MAX_STREAMS ->
                {
                    if (varLenInt.tryDecode(byteBuffer, v -> maxStreams = v))
                    {
                        if (maxStreams > StreamId.MAX_PROGRESSIVE)
                            throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_max_streams_value", frameType);
                        return result();
                    }
                }
            }
        }
        return null;
    }

    private MaxStreamsFrame result()
    {
        MaxStreamsFrame frame = new MaxStreamsFrame(maxStreams, frameType == 0x12);
        state = State.FRAME_TYPE;
        frameType = 0;
        maxStreams = 0;
        return frame;
    }

    private enum State
    {
        FRAME_TYPE, MAX_STREAMS
    }
}
