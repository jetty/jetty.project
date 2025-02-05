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

package org.eclipse.jetty.quic.api.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;

public class StreamFrame extends Frame.WithStreamId
{
    public static final long END_STREAM_MASK = 0x01;
    public static final long LENGTH_MASK = 0x02;
    public static final long OFFSET_MASK = 0x04;

    private static long toFrameType(boolean hasOffset, boolean hasLength, boolean endStream)
    {
        long result = 0x08;
        if (hasOffset)
            result |= OFFSET_MASK;
        if (hasLength)
            result |= LENGTH_MASK;
        if (endStream)
            result |= END_STREAM_MASK;
        return result;
    }

    private final long offset;
    private final ByteBuffer data;
    private final int length;
    private final boolean endStream;
    private final boolean endData;

    /**
     * <p>Creates the first stream frame with {@code offset=0} for a {@link Stream}.</p>
     * <p>Applications should use this constructor in conjunction with
     * {@link Session#newStream(long, Stream.Listener)}, and for subsequent
     * data to send on the same stream use {@link Stream#data(boolean, java.util.List, org.eclipse.jetty.util.Promise.Invocable)},
     * so that the implementation can compute the {@code offset} on behalf of the application.</p>
     *
     * @param streamId the stream id generated using {@link Session#newStreamId(boolean)}
     * @param data the data bytes to send
     * @param endStream whether the data is the last to be sent
     */
    public StreamFrame(long streamId, ByteBuffer data, boolean endStream)
    {
        this(streamId, data, 0, endStream);
    }

    /**
     * <p>Creates a stream frame with the given {@code offset} for a {@link Stream}.</p>
     * <p>Applications should not use this constructor.</p>
     *
     * @param streamId the stream id generated using {@link Session#newStreamId(boolean)}
     * @param data the data bytes to send
     * @param offset the data offset
     * @param endStream whether the data is the last to be sent
     */
    public StreamFrame(long streamId, ByteBuffer data, long offset, boolean endStream)
    {
        this(streamId, data, offset, true, endStream);
    }

    /**
     * <p>Creates a stream frame for a {@link Stream}.</p>
     * <p>Applications should not use this constructor.</p>
     *
     * @param streamId the stream id generated using {@link Session#newStreamId(boolean)}
     * @param data the data bytes to send
     * @param offset the data offset
     * @param hasLength whether the frame explicitly specifies the data length
     * @param endStream whether the data is the last to be sent
     */
    public StreamFrame(long streamId, ByteBuffer data, long offset, boolean hasLength, boolean endStream)
    {
        this(toFrameType(offset >= 0, hasLength, endStream), streamId, data, offset, true);
    }

    /**
     * <p>Creates a stream frame for a {@link Stream}.</p>
     * <p>Applications should not use this constructor.</p>
     *
     * @param frameType the frame type
     * @param streamId the stream id generated using {@link Session#newStreamId(boolean)}
     * @param data the data bytes to send
     * @param offset the data offset
     */
    public StreamFrame(long frameType, long streamId, ByteBuffer data, long offset, boolean endData)
    {
        super(frameType, streamId);
        this.offset = offset < 0 ? 0 : offset;
        this.data = data;
        this.length = data.remaining();
        this.endStream = (frameType & END_STREAM_MASK) == END_STREAM_MASK;
        this.endData = endData;
    }

    public long getOffset()
    {
        return offset;
    }

    public ByteBuffer getData()
    {
        return data;
    }

    public int getLength()
    {
        return length;
    }

    public boolean isEndStream()
    {
        return endStream;
    }

    public boolean isEndData()
    {
        return endData;
    }

    @Override
    public String toString()
    {
        return "%s[offset=%d,length=%d/%d,last=%b]".formatted(
            super.toString(),
            getOffset(),
            getData().remaining(),
            getLength(),
            isEndStream()
        );
    }
}
