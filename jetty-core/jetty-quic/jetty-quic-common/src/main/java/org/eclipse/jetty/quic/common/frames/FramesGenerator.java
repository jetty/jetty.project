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

package org.eclipse.jetty.quic.common.frames;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.jetty.quic.api.frames.AckFrame;
import org.eclipse.jetty.quic.api.frames.ConnectionCloseFrame;
import org.eclipse.jetty.quic.api.frames.CryptoFrame;
import org.eclipse.jetty.quic.api.frames.DataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.MaxDataFrame;
import org.eclipse.jetty.quic.api.frames.MaxStreamsFrame;
import org.eclipse.jetty.quic.api.frames.NewConnectionIdFrame;
import org.eclipse.jetty.quic.api.frames.NewTokenFrame;
import org.eclipse.jetty.quic.api.frames.PathChallengeFrame;
import org.eclipse.jetty.quic.api.frames.PathResponseFrame;
import org.eclipse.jetty.quic.api.frames.ResetStreamFrame;
import org.eclipse.jetty.quic.api.frames.RetireConnectionIdFrame;
import org.eclipse.jetty.quic.api.frames.StopSendingFrame;
import org.eclipse.jetty.quic.api.frames.StreamDataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.api.frames.StreamMaxDataFrame;
import org.eclipse.jetty.quic.api.frames.StreamsBlockedFrame;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.quic.util.QuicException;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.util.buffer.BufferAccumulator;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class FramesGenerator
{
    private final ByteBufferPool bufferPool;
    private boolean useDirectBuffers;

    public FramesGenerator(ByteBufferPool bufferPool)
    {
        this.bufferPool = bufferPool;
        setUseDirectBuffers(true);
    }

    public ByteBufferPool getByteBufferPool()
    {
        return bufferPool;
    }

    public boolean isUseDirectBuffers()
    {
        return useDirectBuffers;
    }

    public void setUseDirectBuffers(boolean useDirectBuffers)
    {
        this.useDirectBuffers = useDirectBuffers;
    }

    public long generateFrame(BufferAccumulator accumulator, Frame frame, long maxBytes)
    {
        long type = frame.type();
        FrameType frameType = FrameType.from(type);
        if (frameType == null)
            throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_frame_type", type);
        return switch (frameType)
        {
            case PADDING, PING, HANDSHAKE_DONE -> generateNoContentFrame(accumulator, frame, maxBytes);
            case ACK -> generateAckFrame(accumulator, (AckFrame)frame, maxBytes);
            case STOP_SENDING -> generateStopSendingFrame(accumulator, (StopSendingFrame)frame, maxBytes);
            case NEW_TOKEN -> generateNewTokenFrame(accumulator, (NewTokenFrame)frame, maxBytes);
            case MAX_DATA -> generateMaxDataFrame(accumulator, (MaxDataFrame)frame, maxBytes);
            case STREAM_MAX_DATA -> generateStreamMaxDataFrame(accumulator, (StreamMaxDataFrame)frame, maxBytes);
            case MAX_STREAMS -> generateMaxStreamsFrame(accumulator, (MaxStreamsFrame)frame, maxBytes);
            case DATA_BLOCKED -> generateDataBlockedFrame(accumulator, (DataBlockedFrame)frame, maxBytes);
            case STREAM_DATA_BLOCKED -> generateStreamDataBlockedFrame(accumulator, (StreamDataBlockedFrame)frame, maxBytes);
            case STREAMS_BLOCKED -> generateStreamsBlockedFrame(accumulator, (StreamsBlockedFrame)frame, maxBytes);
            case NEW_CONNECTION_ID -> generateNewConnectionIdFrame(accumulator, (NewConnectionIdFrame)frame, maxBytes);
            case RETIRE_CONNECTION_ID -> generateRetireConnectionIdFrame(accumulator, (RetireConnectionIdFrame)frame, maxBytes);
            case PATH_CHALLENGE -> generatePathChallengeFrame(accumulator, (PathChallengeFrame)frame, maxBytes);
            case PATH_RESPONSE -> generatePathResponseFrame(accumulator, (PathResponseFrame)frame, maxBytes);
            case CONNECTION_CLOSE -> generateConnectionCloseFrame(accumulator, (ConnectionCloseFrame)frame, maxBytes);
            default -> throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_frame_type", type);
        };
    }

    private long generateNoContentFrame(BufferAccumulator accumulator, Frame frame, long maxBytes)
    {
        long frameType = frame.type();
        int capacity = VarLenInt.length(frameType);
        if (capacity > maxBytes)
            return 0;

        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);
        VarLenInt.encode(buffer, frameType);
        return capacity;
    }

    private long generateAckFrame(BufferAccumulator accumulator, AckFrame frame, long maxBytes)
    {
        long frameType = frame.type();
        int capacity = VarLenInt.length(frameType);
        long largestAcknowledged = frame.largestAcknowledged();
        capacity += VarLenInt.length(largestAcknowledged);
        long encodedAckDelay = frame.encodedAckDelay();
        capacity += VarLenInt.length(encodedAckDelay);
        List<AckFrame.AckRange> ranges = frame.ackRanges();
        int rangesCount = ranges.size();
        capacity += VarLenInt.length(rangesCount);
        long firstRangeLength = frame.firstRangeLength();
        capacity += VarLenInt.length(firstRangeLength);
        for (AckFrame.AckRange range : ranges)
        {
            capacity += VarLenInt.length(range.gap());
            capacity += VarLenInt.length(range.length());
        }
        if (capacity > maxBytes)
            return 0;

        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);
        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, largestAcknowledged);
        VarLenInt.encode(buffer, encodedAckDelay);
        VarLenInt.encode(buffer, rangesCount);
        VarLenInt.encode(buffer, firstRangeLength);
        for (AckFrame.AckRange range : ranges)
        {
            VarLenInt.encode(buffer, range.gap());
            VarLenInt.encode(buffer, range.length());
        }
        return capacity;
    }

    public GeneratedFrame generateResetStreamFrame(BufferAccumulator accumulator, ResetStreamFrame frame, long finalSize, long maxBytes)
    {
        long frameType = frame.type();
        int capacity = VarLenInt.length(frameType);
        long streamId = frame.streamId();
        capacity += VarLenInt.length(streamId);
        long error = frame.applicationErrorCode();
        capacity += VarLenInt.length(error);
        capacity += VarLenInt.length(finalSize);
        if (capacity > maxBytes)
            return null;

        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);
        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, streamId);
        VarLenInt.encode(buffer, error);
        VarLenInt.encode(buffer, finalSize);
        return new GeneratedFrame(new ResetStreamFrame(streamId, error, finalSize), capacity);
    }

    private long generateStopSendingFrame(BufferAccumulator accumulator, StopSendingFrame frame, long maxBytes)
    {
        long frameType = frame.type();
        int capacity = VarLenInt.length(frameType);
        long streamId = frame.streamId();
        capacity += VarLenInt.length(streamId);
        long error = frame.applicationErrorCode();
        capacity += VarLenInt.length(error);
        if (capacity > maxBytes)
            return 0;

        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);
        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, streamId);
        VarLenInt.encode(buffer, error);
        return capacity;
    }

    public GeneratedFrame generateCryptoFrame(BufferAccumulator accumulator, CryptoFrame frame, long offset, long maxBytes)
    {
        long frameType = frame.type();
        int capacity = VarLenInt.length(frameType);
        capacity += VarLenInt.length(offset);

        // Same logic as generateStreamFrame().
        long remaining = frame.remaining();
        long estimatedDataLength = Math.min(remaining, maxBytes - capacity);
        if (estimatedDataLength < 0)
            return null;
        long dataLength = Math.min(remaining, maxBytes - capacity - VarLenInt.length(estimatedDataLength));
        if (dataLength < 0)
            return null;
        // Do not generate empty frames if there is data to send.
        if (dataLength == 0 && remaining > 0)
            return null;
        // The length field is encoded with the actual data length,
        // which may require fewer bytes than the estimated data length.
        capacity += VarLenInt.length(dataLength);

        RetainableByteBuffer.Mutable header = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(header);
        VarLenInt.encode(header, frameType);
        VarLenInt.encode(header, offset);
        VarLenInt.encode(header, dataLength);

        try (RetainableByteBuffer buffer = frame.acquire())
        {
            RetainableByteBuffer slice = buffer.sliceAndConsume(dataLength);
            accumulator.addRetained(slice);
            CryptoFrame sliced = new CryptoFrame(offset, slice);
            return new GeneratedFrame(sliced, capacity + dataLength);
        }
    }

    private long generateNewTokenFrame(BufferAccumulator accumulator, NewTokenFrame frame, long maxBytes)
    {
        long frameType = frame.type();
        int capacity = VarLenInt.length(frameType);
        byte[] token = frame.token();
        capacity += VarLenInt.length(token.length);
        capacity += token.length;
        if (capacity > maxBytes)
            return 0;

        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);
        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, token.length);
        buffer.put(token);
        return capacity;
    }

    /// Generates a [StreamFrame] with the given `offset`.
    ///
    /// The generation is capped by:
    ///
    /// * `maxData`, the maximum number of data bytes allowed by flow control
    /// * `maxBytes`, the maximum number of frame bytes allowed by congestion control
    public GeneratedFrame generateStreamFrame(BufferAccumulator accumulator, StreamFrame frame, long offset, long maxData, long maxBytes)
    {
        assert maxData > 0 || (maxData == 0 && frame.remaining() == 0);

        long frameType = frame.type();
        int capacity = VarLenInt.length(frameType);
        long streamId = frame.streamId();
        capacity += VarLenInt.length(streamId);
        boolean hasOffset = offset > 0 || (frameType & StreamFrame.OFFSET_MASK) == StreamFrame.OFFSET_MASK;
        if (hasOffset)
        {
            capacity += VarLenInt.length(offset);
            // Make sure the offset bit is set.
            frameType |= StreamFrame.OFFSET_MASK;
        }
        boolean hasLength = (frameType & StreamFrame.LENGTH_MASK) == StreamFrame.LENGTH_MASK;

        if (capacity > maxBytes)
            return null;

        long remaining = frame.remaining();
        long dataBytes = Math.min(remaining, maxData);

        // Handle the case where the bytes to send are more than they fit in maxBytes.
        // The data bytes are calculated without taking into account the length field.
        // This means that the data bytes are temporarily overestimated, which may lead
        // to reserving more bytes for the length field than necessary.
        // VarLenInt encodes 0-63 in 1 byte, and 64-16383 in 2 bytes.
        // If the data length below is estimated at 64, this means that the length field
        // will occupy 2 bytes; however, when correcting the available frame bytes subtracting
        // the length field length (2 bytes), the data length will be 62, which can be encoded
        // as just 1 byte, so 63 data bytes could have been generated, but we don't bother.
        long estimatedDataLength = Math.min(dataBytes, maxBytes - capacity);
        long dataLength = estimatedDataLength;
        if (hasLength)
        {
            dataLength = Math.min(dataBytes, maxBytes - capacity - VarLenInt.length(estimatedDataLength));
            if (dataLength < 0)
                return null;
            capacity += VarLenInt.length(dataLength);
        }

        // Do not generate empty frames if there is data to send.
        if (dataLength == 0 && dataBytes > 0)
            return null;

        // Clear the endStream bit if the frame cannot be fully generated.
        boolean excessData = remaining > dataLength;
        if (frame.isEndStream() && excessData)
            frameType = frameType & ~StreamFrame.END_STREAM_MASK;

        RetainableByteBuffer.Mutable header = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(header);
        VarLenInt.encode(header, frameType);
        VarLenInt.encode(header, streamId);
        if (hasOffset)
            VarLenInt.encode(header, offset);
        if (hasLength)
            VarLenInt.encode(header, dataLength);

        try (RetainableByteBuffer buffer = frame.acquire())
        {
            RetainableByteBuffer slice = buffer.sliceAndConsume(dataLength);
            accumulator.addRetained(slice);
            StreamFrame sliced = new StreamFrame(frameType, streamId, slice, offset, frame.isEndData());
            return new GeneratedFrame(sliced, capacity + dataLength);
        }
    }

    private long generateMaxDataFrame(BufferAccumulator accumulator, MaxDataFrame frame, long maxBytes)
    {
        long frameType = frame.type();
        int capacity = VarLenInt.length(frameType);
        long maxData = frame.maxData();
        capacity += VarLenInt.length(maxData);
        if (capacity > maxBytes)
            return 0;

        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);
        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, maxData);
        return capacity;
    }

    private long generateStreamMaxDataFrame(BufferAccumulator accumulator, StreamMaxDataFrame frame, long maxBytes)
    {
        long frameType = frame.type();
        int capacity = VarLenInt.length(frameType);
        long streamId = frame.streamId();
        capacity += VarLenInt.length(streamId);
        long maxData = frame.maxData();
        capacity += VarLenInt.length(maxData);
        if (capacity > maxBytes)
            return 0;

        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);
        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, streamId);
        VarLenInt.encode(buffer, maxData);
        return capacity;
    }

    private long generateMaxStreamsFrame(BufferAccumulator accumulator, MaxStreamsFrame frame, long maxBytes)
    {
        long frameType = frame.type();
        int capacity = VarLenInt.length(frameType);
        long maxStreams = frame.maxStreams();
        capacity += VarLenInt.length(maxStreams);
        if (capacity > maxBytes)
            return 0;

        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);
        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, maxStreams);
        return capacity;
    }

    private long generateDataBlockedFrame(BufferAccumulator accumulator, DataBlockedFrame frame, long maxBytes)
    {
        long frameType = frame.type();
        int capacity = VarLenInt.length(frameType);
        long offset = frame.offset();
        capacity += VarLenInt.length(offset);
        if (capacity > maxBytes)
            return 0;

        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);
        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, offset);
        return capacity;
    }

    private long generateStreamDataBlockedFrame(BufferAccumulator accumulator, StreamDataBlockedFrame frame, long maxBytes)
    {
        long frameType = frame.type();
        int capacity = VarLenInt.length(frameType);
        long streamId = frame.streamId();
        capacity += VarLenInt.length(streamId);
        long offset = frame.offset();
        capacity += VarLenInt.length(offset);
        if (capacity > maxBytes)
            return 0;

        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);
        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, streamId);
        VarLenInt.encode(buffer, offset);
        return capacity;
    }

    private long generateStreamsBlockedFrame(BufferAccumulator accumulator, StreamsBlockedFrame frame, long maxBytes)
    {
        long frameType = frame.type();
        int capacity = VarLenInt.length(frameType);
        long maxStreams = frame.maxStreams();
        capacity += VarLenInt.length(maxStreams);
        if (capacity > maxBytes)
            return 0;

        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);
        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, maxStreams);
        return capacity;
    }

    private long generateNewConnectionIdFrame(BufferAccumulator accumulator, NewConnectionIdFrame frame, long maxBytes)
    {
        long frameType = frame.type();
        int capacity = VarLenInt.length(frameType);
        long sequenceNumber = frame.sequenceNumber();
        capacity += VarLenInt.length(sequenceNumber);
        long retirePriorTo = frame.retirePriorTo();
        capacity += VarLenInt.length(retirePriorTo);
        byte[] connectionId = frame.connectionId();
        // The connection id length is a single byte.
        capacity += 1;
        capacity += connectionId.length;
        byte[] resetToken = frame.resetToken();
        capacity += resetToken.length;
        if (capacity > maxBytes)
            return 0;

        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);
        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, sequenceNumber);
        VarLenInt.encode(buffer, retirePriorTo);
        buffer.put((byte)connectionId.length);
        buffer.put(connectionId);
        buffer.put(resetToken);
        return capacity;
    }

    private long generateRetireConnectionIdFrame(BufferAccumulator accumulator, RetireConnectionIdFrame frame, long maxBytes)
    {
        long frameType = frame.type();
        int capacity = VarLenInt.length(frameType);
        long sequenceNumber = frame.sequenceNumber();
        capacity += VarLenInt.length(sequenceNumber);
        if (capacity > maxBytes)
            return 0;

        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);
        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, sequenceNumber);
        return capacity;
    }

    private long generatePathChallengeFrame(BufferAccumulator accumulator, PathChallengeFrame frame, long maxBytes)
    {
        long frameType = frame.type();
        int capacity = VarLenInt.length(frameType);
        capacity += Long.BYTES;
        if (capacity > maxBytes)
            return 0;

        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);
        VarLenInt.encode(buffer, frameType);
        buffer.putLong(frame.data());
        return capacity;
    }

    private long generatePathResponseFrame(BufferAccumulator accumulator, PathResponseFrame frame, long maxBytes)
    {
        long frameType = frame.type();
        int capacity = VarLenInt.length(frameType);
        capacity += Long.BYTES;
        if (capacity > maxBytes)
            return 0;

        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);
        VarLenInt.encode(buffer, frameType);
        buffer.putLong(frame.data());
        return capacity;
    }

    private long generateConnectionCloseFrame(BufferAccumulator accumulator, ConnectionCloseFrame frame, long maxBytes)
    {
        long frameType = frame.type();
        int capacity = VarLenInt.length(frameType);
        long errorCode = frame.errorCode();
        capacity += VarLenInt.length(errorCode);
        boolean hasCauseFrameType = frameType == 0x1C;
        long causeFrameType = frame.causeFrameType();
        if (hasCauseFrameType)
            capacity += VarLenInt.length(causeFrameType);
        byte[] reason = frame.reason().getBytes(StandardCharsets.UTF_8);
        capacity += VarLenInt.length(reason.length);
        capacity += reason.length;
        if (capacity > maxBytes)
            return 0;

        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, isUseDirectBuffers());
        accumulator.addRetained(buffer);
        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, errorCode);
        if (hasCauseFrameType)
            VarLenInt.encode(buffer, causeFrameType);
        VarLenInt.encode(buffer, reason.length);
        buffer.put(reason);
        return capacity;
    }
}
