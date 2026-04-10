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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
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
import org.eclipse.jetty.quic.api.frames.ResetFrame;
import org.eclipse.jetty.quic.api.frames.RetireConnectionIdFrame;
import org.eclipse.jetty.quic.api.frames.StopSendingFrame;
import org.eclipse.jetty.quic.api.frames.StreamDataBlockedFrame;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.api.frames.StreamMaxDataFrame;
import org.eclipse.jetty.quic.api.frames.StreamsBlockedFrame;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.quic.util.QuicException;
import org.eclipse.jetty.quic.util.VarLenInt;

public class FramesGenerator
{
    private final ByteBufferPool byteBufferPool;
    private boolean useDirectBuffers;

    public FramesGenerator(ByteBufferPool byteBufferPool)
    {
        this.byteBufferPool = byteBufferPool;
        setUseDirectBuffers(true);
    }

    public ByteBufferPool getByteBufferPool()
    {
        return byteBufferPool;
    }

    public boolean isUseDirectBuffers()
    {
        return useDirectBuffers;
    }

    public void setUseDirectBuffers(boolean useDirectBuffers)
    {
        this.useDirectBuffers = useDirectBuffers;
    }

    public long generateFrame(RetainableByteBuffer.Mutable accumulator, Frame frame, long maxBytes)
    {
        long type = frame.type();
        FrameType frameType = FrameType.from(type);
        if (frameType == null)
            throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_frame_type", type);
        return switch (frameType)
        {
            case PADDING, PING, HANDSHAKE_DONE -> generateNoContentFrame(accumulator, frame, maxBytes);
            case ACK -> generateAckFrame(accumulator, (AckFrame)frame, maxBytes);
            case RESET_STREAM -> generateResetStreamFrame(accumulator, (ResetFrame)frame, maxBytes);
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

    private int rollback(RetainableByteBuffer.Mutable accumulator, long limit)
    {
        accumulator.limit(limit);
        return 0;
    }

    private long generateNoContentFrame(RetainableByteBuffer.Mutable accumulator, Frame frame, long maxBytes)
    {
        long limit = accumulator.size();
        int generated = VarLenInt.encode(accumulator, frame.type());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        return generated;
    }

    private long generateAckFrame(RetainableByteBuffer.Mutable accumulator, AckFrame frame, long maxBytes)
    {
        long limit = accumulator.size();
        int generated = VarLenInt.encode(accumulator, frame.type());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += VarLenInt.encode(accumulator, frame.largestAcknowledged());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += VarLenInt.encode(accumulator, frame.encodedAckDelay());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        List<AckFrame.AckRange> ranges = frame.ackRanges();
        generated += VarLenInt.encode(accumulator, ranges.size());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += VarLenInt.encode(accumulator, frame.firstRangeLength());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        long rangeLimit = accumulator.size();
        for (AckFrame.AckRange range : ranges)
        {
            generated += VarLenInt.encode(accumulator, range.gap());
            if (maxBytes - generated < 0)
                return rollback(accumulator, rangeLimit);
            generated += VarLenInt.encode(accumulator, range.length());
            if (maxBytes - generated < 0)
                return rollback(accumulator, rangeLimit);
            rangeLimit = accumulator.size();
        }
        return generated;
    }

    private long generateResetStreamFrame(RetainableByteBuffer.Mutable accumulator, ResetFrame frame, long maxBytes)
    {
        long limit = accumulator.size();
        int generated = VarLenInt.encode(accumulator, frame.type());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += VarLenInt.encode(accumulator, frame.streamId());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += VarLenInt.encode(accumulator, frame.applicationErrorCode());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += VarLenInt.encode(accumulator, frame.finalSize());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        return generated;
    }

    private long generateStopSendingFrame(RetainableByteBuffer.Mutable accumulator, StopSendingFrame frame, long maxBytes)
    {
        long limit = accumulator.size();
        int generated = VarLenInt.encode(accumulator, frame.type());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += VarLenInt.encode(accumulator, frame.streamId());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += VarLenInt.encode(accumulator, frame.applicationErrorCode());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        return generated;
    }

    public GeneratedFrame generateCryptoFrame(RetainableByteBuffer.Mutable accumulator, CryptoFrame frame, long offset, final long maxBytes)
    {
        int capacity = VarLenInt.length(frame.type());
        capacity += VarLenInt.length(offset);

        // Same logic as generateStreamFrame().
        RetainableByteBuffer data = frame.data();
        long estimatedDataLength = Math.min(data.size(), maxBytes - capacity);
        if (estimatedDataLength <= 0)
            return null;
        capacity += VarLenInt.length(estimatedDataLength);
        long dataLength = Math.min(data.size(), maxBytes - capacity);
        if (dataLength <= 0)
            return null;
        boolean excessData = data.size() > dataLength;

        long generated = VarLenInt.encode(accumulator, frame.type());
        generated += VarLenInt.encode(accumulator, offset);
        generated += VarLenInt.encode(accumulator, dataLength);
        if (excessData)
        {
            RetainableByteBuffer slice = data.slice(dataLength);
            data.skip(dataLength);
            generated += dataLength;
            accumulator.add(slice);
            return new GeneratedFrame(new CryptoFrame(offset, slice), generated);
        }
        else
        {
            generated += data.size();
            RetainableByteBuffer slice = data.slice();
            accumulator.add(slice);
            return new GeneratedFrame(new CryptoFrame(offset, slice), generated);
        }
    }

    private long generateNewTokenFrame(RetainableByteBuffer.Mutable accumulator, NewTokenFrame frame, long maxBytes)
    {
        long limit = accumulator.size();
        int generated = VarLenInt.encode(accumulator, frame.type());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        byte[] token = frame.token();
        generated += VarLenInt.encode(accumulator, token.length);
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += token.length;
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        accumulator.append(ByteBuffer.wrap(token));
        return generated;
    }

    public GeneratedFrame generateStreamFrame(RetainableByteBuffer.Mutable accumulator, StreamFrame frame, long offset, long maxBytes)
    {
        long frameType = frame.type();
        int capacity = VarLenInt.length(frameType);
        long streamId = frame.streamId();
        capacity += VarLenInt.length(streamId);
        boolean hasOffset = offset > 0 || (frameType & StreamFrame.OFFSET_MASK) == StreamFrame.OFFSET_MASK;
        if (hasOffset)
            capacity += VarLenInt.length(offset);
        boolean hasLength = (frameType & StreamFrame.LENGTH_MASK) == StreamFrame.LENGTH_MASK;

        // Handle the case where the bytes to send are more than they fit in the frame.
        // The data length is calculated without taking into account the length field.
        // This means that the data length is temporarily overestimated, which may lead
        // to reserving more bytes for the length field than necessary.
        // VarLenInt encodes 0-63 in 1 byte, and 64-16383 in 2 bytes.
        // If the data length below is estimated at 64, this means that the length field
        // will occupy 2 bytes; however, when correcting the data length subtracting the
        // length field length (2 bytes), the data length will be 62, which can be encoded
        // as just 1 byte, so 63 data bytes could have been generated, but we don't bother.
        RetainableByteBuffer data = frame.data();
        long estimatedDataLength = Math.min(data.size(), maxBytes - capacity);
        if (estimatedDataLength < 0 || (estimatedDataLength == 0 && !frame.isEndStream()))
            return null;
        int dataLengthLength = 0;
        if (hasLength)
            dataLengthLength = VarLenInt.length(estimatedDataLength);
        capacity += dataLengthLength;
        long dataLength = Math.min(data.size(), maxBytes - capacity);
        if (dataLength < 0 || (dataLength == 0 && !frame.isEndStream()))
            return null;

        boolean endStream = (frameType & StreamFrame.END_STREAM_MASK) == StreamFrame.END_STREAM_MASK;
        // Clear the endStream bit if the frame cannot be fully generated.
        boolean excessData = data.size() > dataLength;
        if (endStream && excessData)
            frameType = frameType & ~StreamFrame.END_STREAM_MASK;

        long generated = VarLenInt.encode(accumulator, frameType);
        generated += VarLenInt.encode(accumulator, streamId);
        if (hasOffset)
            generated += VarLenInt.encode(accumulator, offset);
        if (hasLength)
            generated += VarLenInt.encode(accumulator, dataLength);
        if (excessData)
        {
            RetainableByteBuffer slice = data.slice(dataLength);
            data.skip(dataLength);
            generated += dataLength;
            accumulator.add(slice);
            return new GeneratedFrame(new StreamFrame(frameType, streamId, slice, offset, frame.isEndData()), generated);
        }
        else
        {
            generated += dataLength;
            RetainableByteBuffer slice = data.slice();
            accumulator.add(slice);
            return new GeneratedFrame(new StreamFrame(frameType, streamId, slice, offset, frame.isEndData()), generated);
        }
    }

    private long generateMaxDataFrame(RetainableByteBuffer.Mutable accumulator, MaxDataFrame frame, long maxBytes)
    {
        long limit = accumulator.size();
        long generated = VarLenInt.encode(accumulator, frame.type());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += VarLenInt.encode(accumulator, frame.maxData());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        return generated;
    }

    private long generateStreamMaxDataFrame(RetainableByteBuffer.Mutable accumulator, StreamMaxDataFrame frame, long maxBytes)
    {
        long limit = accumulator.size();
        long generated = VarLenInt.encode(accumulator, frame.type());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += VarLenInt.encode(accumulator, frame.streamId());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += VarLenInt.encode(accumulator, frame.maxData());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        return generated;
    }

    private long generateMaxStreamsFrame(RetainableByteBuffer.Mutable accumulator, MaxStreamsFrame frame, long maxBytes)
    {
        long limit = accumulator.size();
        long generated = VarLenInt.encode(accumulator, frame.type());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += VarLenInt.encode(accumulator, frame.maxStreams());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        return generated;
    }

    private long generateDataBlockedFrame(RetainableByteBuffer.Mutable accumulator, DataBlockedFrame frame, long maxBytes)
    {
        long limit = accumulator.size();
        long generated = VarLenInt.encode(accumulator, frame.type());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += VarLenInt.encode(accumulator, frame.offset());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        return generated;
    }

    private long generateStreamDataBlockedFrame(RetainableByteBuffer.Mutable accumulator, StreamDataBlockedFrame frame, long maxBytes)
    {
        long limit = accumulator.size();
        long generated = VarLenInt.encode(accumulator, frame.type());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += VarLenInt.encode(accumulator, frame.streamId());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += VarLenInt.encode(accumulator, frame.offset());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        return generated;
    }

    private long generateStreamsBlockedFrame(RetainableByteBuffer.Mutable accumulator, StreamsBlockedFrame frame, long maxBytes)
    {
        long limit = accumulator.size();
        long generated = VarLenInt.encode(accumulator, frame.type());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += VarLenInt.encode(accumulator, frame.maxStreams());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        return generated;
    }

    private long generateNewConnectionIdFrame(RetainableByteBuffer.Mutable accumulator, NewConnectionIdFrame frame, long maxBytes)
    {
        long limit = accumulator.size();
        long generated = VarLenInt.encode(accumulator, frame.type());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += VarLenInt.encode(accumulator, frame.sequenceNumber());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += VarLenInt.encode(accumulator, frame.retirePriorTo());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        byte[] connectionId = frame.connectionId();
        generated += VarLenInt.encode(accumulator, connectionId.length);
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += connectionId.length;
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        accumulator.append(ByteBuffer.wrap(connectionId));
        byte[] resetToken = frame.resetToken();
        generated += resetToken.length;
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        accumulator.append(ByteBuffer.wrap(resetToken));
        return generated;
    }

    private long generateRetireConnectionIdFrame(RetainableByteBuffer.Mutable accumulator, RetireConnectionIdFrame frame, long maxBytes)
    {
        long limit = accumulator.size();
        long generated = VarLenInt.encode(accumulator, frame.type());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += VarLenInt.encode(accumulator, frame.sequenceNumber());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        return generated;
    }

    private long generatePathChallengeFrame(RetainableByteBuffer.Mutable accumulator, PathChallengeFrame frame, long maxBytes)
    {
        long limit = accumulator.size();
        long generated = VarLenInt.encode(accumulator, frame.type());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += 8;
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        accumulator.putLong(frame.data());
        return generated;
    }

    private long generatePathResponseFrame(RetainableByteBuffer.Mutable accumulator, PathResponseFrame frame, long maxBytes)
    {
        long limit = accumulator.size();
        long generated = VarLenInt.encode(accumulator, frame.type());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += 8;
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        accumulator.putLong(frame.data());
        return generated;
    }

    private long generateConnectionCloseFrame(RetainableByteBuffer.Mutable accumulator, ConnectionCloseFrame frame, long maxBytes)
    {
        long limit = accumulator.size();
        long generated = VarLenInt.encode(accumulator, frame.type());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += VarLenInt.encode(accumulator, frame.errorCode());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        if (frame.type() == 0x1C)
        {
            generated += VarLenInt.encode(accumulator, frame.causeFrameType());
            if (maxBytes - generated < 0)
                return rollback(accumulator, limit);
        }
        String reason = frame.reason();
        ByteBuffer reasonBytes = StandardCharsets.UTF_8.encode(reason);
        generated += VarLenInt.encode(accumulator, reasonBytes.remaining());
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        generated += reasonBytes.remaining();
        if (maxBytes - generated < 0)
            return rollback(accumulator, limit);
        accumulator.append(RetainableByteBuffer.wrap(reasonBytes));
        return generated;
    }
}
