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

import java.util.List;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.Session;
import org.eclipse.jetty.quic.api.Stream;
import org.eclipse.jetty.util.Promise;

/// A QUIC frame carrying stream data bytes.
public final class StreamFrame extends Frame.WithStreamId.Abstract implements Frame.WithData
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
    private final RetainableByteBuffer data;
    private final long length;
    private final boolean endStream;
    private final boolean endData;

    /// Creates the first stream frame with `offset=0` for a new [Stream].
    ///
    /// Applications should use this constructor in conjunction with
    /// [Session#newStream(long, Stream.Listener)].
    /// For subsequent data to be sent on the same stream, applications should use
    /// [Stream#data(boolean, java.util.List, org.eclipse.jetty.util.Promise.Invocable)],
    /// so that the implementation can compute the `offset` on behalf of the application.
    ///
    /// @param streamId the stream id generated using [Session#newStreamId(boolean)]
    /// @param data the data bytes to send
    /// @param endStream whether the data is the last to be sent
    public StreamFrame(long streamId, RetainableByteBuffer data, boolean endStream)
    {
        this(streamId, data, 0, endStream);
    }

    /// Creates a stream frame with the given `offset` for a [Stream].
    ///
    /// Applications should not use this constructor but instead use
    /// [Stream#data(boolean, List, Promise.Invocable)].
    ///
    /// @param streamId the stream id generated using [Session#newStreamId(boolean)]
    /// @param data the data bytes to send
    /// @param offset the data offset
    /// @param endStream whether the data is the last to be sent
    public StreamFrame(long streamId, RetainableByteBuffer data, long offset, boolean endStream)
    {
        this(streamId, data, offset, true, endStream);
    }

    /// Creates a stream frame for a [Stream].
    ///
    /// Applications should not use this constructor but instead
    /// use [Stream#data(boolean, List, Promise.Invocable)].
    ///
    /// @param streamId the stream id generated using [Session#newStreamId(boolean)]
    /// @param data the data bytes to send
    /// @param offset the data offset
    /// @param hasLength whether the frame explicitly specifies the data length
    /// @param endStream whether the data is the last to be sent
    public StreamFrame(long streamId, RetainableByteBuffer data, long offset, boolean hasLength, boolean endStream)
    {
        this(toFrameType(offset >= 0, hasLength, endStream), streamId, data, offset, true);
    }

    /// Creates a stream frame for a [Stream].
    ///
    /// Applications should not use this constructor but instead
    /// use [Stream#data(boolean, List, Promise.Invocable)].
    ///
    /// @param frameType the frame type
    /// @param streamId the stream id generated using [Session#newStreamId(boolean)]
    /// @param data the data bytes to send
    /// @param offset the data offset
    public StreamFrame(long frameType, long streamId, RetainableByteBuffer data, long offset, boolean endData)
    {
        super(frameType, streamId);
        this.offset = offset < 0 ? 0 : offset;
        this.data = data;
        this.length = data.size();
        this.endStream = (frameType & END_STREAM_MASK) == END_STREAM_MASK;
        this.endData = endData;
    }

    /// @return the stream offset of the data bytes carried by this frame
    @Override
    public long offset()
    {
        return offset;
    }

    /// @return the data bytes
    @Override
    public RetainableByteBuffer data()
    {
        return data;
    }

    /// @return the number of data bytes
    @Override
    public long length()
    {
        return length;
    }

    /// @return whether this frame is the last in the stream
    public boolean isEndStream()
    {
        return endStream;
    }

    /// Returns whether this frame is the last of a larger frame that has been split.
    ///
    /// This is only useful for QUIC-STREAMS, since in QUIC frames are never split.
    ///
    /// @return whether this frame is the last chunk of a larger frame that has been split.
    public boolean isEndData()
    {
        return endData;
    }

    @Override
    public String toString()
    {
        return "%s[offset=%d,length=%d/%d,last=%b]".formatted(
            super.toString(),
            offset(),
            data().size(),
            length(),
            isEndStream()
        );
    }
}
