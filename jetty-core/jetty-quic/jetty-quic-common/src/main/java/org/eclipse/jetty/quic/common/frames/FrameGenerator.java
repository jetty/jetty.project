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
import org.eclipse.jetty.util.BufferUtil;

public class FrameGenerator
{
    private final ByteBufferPool byteBufferPool;
    private boolean useDirectBuffers;

    public FrameGenerator(ByteBufferPool byteBufferPool)
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

    public long generate(ByteBufferPool.Accumulator accumulator, Frame frame)
    {
        long type = frame.getFrameType();
        FrameType frameType = FrameType.from(type);
        if (frameType == null)
            throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_frame_type", type);
        return switch (frameType)
        {
            case PADDING, PING, HANDSHAKE_DONE -> generateNoContentFrame(accumulator, frame);
            case ACK -> generateAckFrame(accumulator, (AckFrame)frame);
            case RESET_STREAM -> generateResetStreamFrame(accumulator, (ResetFrame)frame);
            case STOP_SENDING -> generateStopSendingFrame(accumulator, (StopSendingFrame)frame);
            case CRYPTO -> generateCryptoFrame(accumulator, (CryptoFrame)frame);
            case NEW_TOKEN -> generateNewTokenFrame(accumulator, (NewTokenFrame)frame);
            case MAX_DATA -> generateMaxDataFrame(accumulator, (MaxDataFrame)frame);
            case STREAM_MAX_DATA -> generateStreamMaxDataFrame(accumulator, (StreamMaxDataFrame)frame);
            case MAX_STREAMS -> generateMaxStreamsFrame(accumulator, (MaxStreamsFrame)frame);
            case DATA_BLOCKED -> generateDataBlockedFrame(accumulator, (DataBlockedFrame)frame);
            case STREAM_DATA_BLOCKED -> generateStreamDataBlockedFrame(accumulator, (StreamDataBlockedFrame)frame);
            case STREAMS_BLOCKED -> generateStreamsBlockedFrame(accumulator, (StreamsBlockedFrame)frame);
            case NEW_CONNECTION_ID -> generateNewConnectionIdFrame(accumulator, (NewConnectionIdFrame)frame);
            case RETIRE_CONNECTION_ID -> generateRetireConnectionIdFrame(accumulator, (RetireConnectionIdFrame)frame);
            case PATH_CHALLENGE -> generatePathChallengeFrame(accumulator, (PathChallengeFrame)frame);
            case PATH_RESPONSE -> generatePathResponseFrame(accumulator, (PathResponseFrame)frame);
            case CONNECTION_CLOSE -> generateConnectionCloseFrame(accumulator, (ConnectionCloseFrame)frame);
            default -> throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_frame_type", type);
        };
    }

    public BytesGenerated generate(ByteBufferPool.Accumulator accumulator, StreamFrame frame, int maxDataBytes, int maxFrameBytes)
    {
        return generateStreamFrame(accumulator, frame, maxDataBytes, maxFrameBytes);
    }

    private long generateNoContentFrame(ByteBufferPool.Accumulator accumulator, Frame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);

        RetainableByteBuffer buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.append(buffer);
        ByteBuffer byteBuffer = buffer.getByteBuffer();

        int position = BufferUtil.flipToFill(byteBuffer);
        VarLenInt.encode(byteBuffer, frameType);
        BufferUtil.flipToFlush(byteBuffer, position);

        return capacity;
    }

    private long generateAckFrame(ByteBufferPool.Accumulator accumulator, AckFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long ackNumber = frame.getLargestAcknowledged();
        capacity += VarLenInt.length(ackNumber);
        long ackDelay = frame.getAckDelay();
        capacity += VarLenInt.length(ackDelay);
        long firstRangeLength = frame.getFirstRangeLength();
        capacity += VarLenInt.length(firstRangeLength);
        List<AckFrame.AckRange> ranges = frame.getAckRanges();
        int rangeSize = ranges.size();
        capacity += VarLenInt.length(rangeSize);
        for (AckFrame.AckRange range : ranges)
        {
            capacity += VarLenInt.length(range.gap());
            capacity += VarLenInt.length(range.length());
        }

        RetainableByteBuffer buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.append(buffer);
        ByteBuffer byteBuffer = buffer.getByteBuffer();

        int position = BufferUtil.flipToFill(byteBuffer);
        VarLenInt.encode(byteBuffer, frameType);
        VarLenInt.encode(byteBuffer, ackNumber);
        VarLenInt.encode(byteBuffer, ackDelay);
        VarLenInt.encode(byteBuffer, firstRangeLength);
        VarLenInt.encode(byteBuffer, rangeSize);
        for (AckFrame.AckRange range : ranges)
        {
            VarLenInt.encode(byteBuffer, range.gap());
            VarLenInt.encode(byteBuffer, range.length());
        }
        BufferUtil.flipToFlush(byteBuffer, position);

        return capacity;
    }

    private long generateResetStreamFrame(ByteBufferPool.Accumulator accumulator, ResetFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long streamId = frame.getStreamId();
        capacity += VarLenInt.length(streamId);
        long errorCode = frame.getApplicationErrorCode();
        capacity += VarLenInt.length(errorCode);
        long finalSize = frame.getFinalSize();
        capacity += VarLenInt.length(finalSize);

        RetainableByteBuffer buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.append(buffer);
        ByteBuffer byteBuffer = buffer.getByteBuffer();

        int position = BufferUtil.flipToFill(byteBuffer);
        VarLenInt.encode(byteBuffer, frameType);
        VarLenInt.encode(byteBuffer, streamId);
        VarLenInt.encode(byteBuffer, errorCode);
        VarLenInt.encode(byteBuffer, finalSize);
        BufferUtil.flipToFlush(byteBuffer, position);

        return capacity;
    }

    private long generateStopSendingFrame(ByteBufferPool.Accumulator accumulator, StopSendingFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long streamId = frame.getStreamId();
        capacity += VarLenInt.length(streamId);
        long errorCode = frame.getApplicationErrorCode();
        capacity += VarLenInt.length(errorCode);

        RetainableByteBuffer buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.append(buffer);
        ByteBuffer byteBuffer = buffer.getByteBuffer();

        int position = BufferUtil.flipToFill(byteBuffer);
        VarLenInt.encode(byteBuffer, frameType);
        VarLenInt.encode(byteBuffer, streamId);
        VarLenInt.encode(byteBuffer, errorCode);
        BufferUtil.flipToFlush(byteBuffer, position);

        return capacity;
    }

    private long generateCryptoFrame(ByteBufferPool.Accumulator accumulator, CryptoFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long offset = frame.getOffset();
        capacity += VarLenInt.length(offset);
        RetainableByteBuffer data = frame.getData();
        int length = data.remaining();
        capacity += VarLenInt.length(length);

        RetainableByteBuffer buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.append(buffer);
        ByteBuffer byteBuffer = buffer.getByteBuffer();

        int position = BufferUtil.flipToFill(byteBuffer);
        VarLenInt.encode(byteBuffer, frameType);
        VarLenInt.encode(byteBuffer, offset);
        VarLenInt.encode(byteBuffer, length);
        BufferUtil.flipToFlush(byteBuffer, position);

        accumulator.append(data);

        return capacity + length;
    }

    private long generateNewTokenFrame(ByteBufferPool.Accumulator accumulator, NewTokenFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        ByteBuffer token = frame.getToken();
        int length = token.remaining();
        capacity += VarLenInt.length(length);

        RetainableByteBuffer buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.append(buffer);
        ByteBuffer byteBuffer = buffer.getByteBuffer();

        int position = BufferUtil.flipToFill(byteBuffer);
        VarLenInt.encode(byteBuffer, frameType);
        VarLenInt.encode(byteBuffer, length);
        BufferUtil.flipToFlush(byteBuffer, position);

        accumulator.append(RetainableByteBuffer.wrap(token));

        return capacity + length;
    }

    private BytesGenerated generateStreamFrame(ByteBufferPool.Accumulator accumulator, StreamFrame frame, int maxDataBytes, int maxFrameBytes)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long streamId = frame.getStreamId();
        capacity += VarLenInt.length(streamId);
        long offset = frame.getOffset();
        boolean hasOffset = offset > 0 || (frameType & StreamFrame.OFFSET_MASK) == StreamFrame.OFFSET_MASK;
        if (hasOffset)
            capacity += VarLenInt.length(offset);
        boolean hasLength = (frameType & StreamFrame.LENGTH_MASK) == StreamFrame.LENGTH_MASK;
        // Handle the case where the bytes to send are more than they fit in the frame.
        int dataLength = maxDataBytes;
        int dataLengthLength = 0;
        if (hasLength)
            dataLengthLength = VarLenInt.length(dataLength);
        int dataBytesInFrame = maxFrameBytes - capacity - dataLengthLength;
        if (dataBytesInFrame < maxDataBytes)
        {
            hasLength = true;
            dataLength = dataBytesInFrame;
            dataLengthLength = VarLenInt.length(dataLength);
        }
        capacity += dataLengthLength;
        boolean endStream = (frameType & StreamFrame.END_STREAM_MASK) == StreamFrame.END_STREAM_MASK;
        // Clear the endStream bit if the frame cannot be fully generated.
        ByteBuffer data = frame.getData();
        boolean dataExceedsFrame = data.remaining() > dataLength;
        if (endStream && dataExceedsFrame)
            frameType = frameType & ~StreamFrame.END_STREAM_MASK;

        RetainableByteBuffer buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.append(buffer);
        ByteBuffer byteBuffer = buffer.getByteBuffer();

        int position = BufferUtil.flipToFill(byteBuffer);
        VarLenInt.encode(byteBuffer, frameType);
        VarLenInt.encode(byteBuffer, streamId);
        if (hasOffset)
            VarLenInt.encode(byteBuffer, offset);
        if (hasLength)
            VarLenInt.encode(byteBuffer, dataLength);
        BufferUtil.flipToFlush(byteBuffer, position);

        if (dataExceedsFrame)
        {
            position = data.position();
            ByteBuffer slice = data.slice(position, dataLength);
            data.position(position + dataLength);
            data = slice;
        }
        accumulator.append(RetainableByteBuffer.wrap(data));

        return new BytesGenerated(dataLength, capacity + dataLength);
    }

    private long generateMaxDataFrame(ByteBufferPool.Accumulator accumulator, MaxDataFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long maxData = frame.getMaxData();
        capacity += VarLenInt.length(maxData);

        RetainableByteBuffer buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.append(buffer);
        ByteBuffer byteBuffer = buffer.getByteBuffer();

        int position = BufferUtil.flipToFill(byteBuffer);
        VarLenInt.encode(byteBuffer, frameType);
        VarLenInt.encode(byteBuffer, maxData);
        BufferUtil.flipToFlush(byteBuffer, position);

        return capacity;
    }

    private long generateStreamMaxDataFrame(ByteBufferPool.Accumulator accumulator, StreamMaxDataFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long streamId = frame.getStreamId();
        capacity += VarLenInt.length(streamId);
        long maxData = frame.getMaxData();
        capacity += VarLenInt.length(maxData);

        RetainableByteBuffer buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.append(buffer);
        ByteBuffer byteBuffer = buffer.getByteBuffer();

        int position = BufferUtil.flipToFill(byteBuffer);
        VarLenInt.encode(byteBuffer, frameType);
        VarLenInt.encode(byteBuffer, streamId);
        VarLenInt.encode(byteBuffer, maxData);
        BufferUtil.flipToFlush(byteBuffer, position);

        return capacity;
    }

    private long generateMaxStreamsFrame(ByteBufferPool.Accumulator accumulator, MaxStreamsFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long maxStreams = frame.getMaxStreams();
        capacity += VarLenInt.length(maxStreams);

        RetainableByteBuffer buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.append(buffer);
        ByteBuffer byteBuffer = buffer.getByteBuffer();

        int position = BufferUtil.flipToFill(byteBuffer);
        VarLenInt.encode(byteBuffer, frameType);
        VarLenInt.encode(byteBuffer, maxStreams);
        BufferUtil.flipToFlush(byteBuffer, position);

        return capacity;
    }

    private long generateDataBlockedFrame(ByteBufferPool.Accumulator accumulator, DataBlockedFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long maxData = frame.getOffset();
        capacity += VarLenInt.length(maxData);

        RetainableByteBuffer buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.append(buffer);
        ByteBuffer byteBuffer = buffer.getByteBuffer();

        int position = BufferUtil.flipToFill(byteBuffer);
        VarLenInt.encode(byteBuffer, frameType);
        VarLenInt.encode(byteBuffer, maxData);
        BufferUtil.flipToFlush(byteBuffer, position);

        return capacity;
    }

    private long generateStreamDataBlockedFrame(ByteBufferPool.Accumulator accumulator, StreamDataBlockedFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long streamId = frame.getStreamId();
        capacity += VarLenInt.length(streamId);
        long maxData = frame.getOffset();
        capacity += VarLenInt.length(maxData);

        RetainableByteBuffer buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.append(buffer);
        ByteBuffer byteBuffer = buffer.getByteBuffer();

        int position = BufferUtil.flipToFill(byteBuffer);
        VarLenInt.encode(byteBuffer, frameType);
        VarLenInt.encode(byteBuffer, streamId);
        VarLenInt.encode(byteBuffer, maxData);
        BufferUtil.flipToFlush(byteBuffer, position);

        return capacity;
    }

    private long generateStreamsBlockedFrame(ByteBufferPool.Accumulator accumulator, StreamsBlockedFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long maxStreams = frame.getMaxStreams();
        capacity += VarLenInt.length(maxStreams);

        RetainableByteBuffer buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.append(buffer);
        ByteBuffer byteBuffer = buffer.getByteBuffer();

        int position = BufferUtil.flipToFill(byteBuffer);
        VarLenInt.encode(byteBuffer, frameType);
        VarLenInt.encode(byteBuffer, maxStreams);
        BufferUtil.flipToFlush(byteBuffer, position);

        return capacity;
    }

    private long generateNewConnectionIdFrame(ByteBufferPool.Accumulator accumulator, NewConnectionIdFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long sequenceNumber = frame.getSequenceNumber();
        capacity += VarLenInt.length(sequenceNumber);
        long retirePriorTo = frame.getRetirePriorTo();
        capacity += VarLenInt.length(retirePriorTo);
        byte[] connectionId = frame.getConnectionId();
        capacity += VarLenInt.length(connectionId.length);

        RetainableByteBuffer buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.append(buffer);
        ByteBuffer byteBuffer = buffer.getByteBuffer();

        int position = BufferUtil.flipToFill(byteBuffer);
        VarLenInt.encode(byteBuffer, frameType);
        VarLenInt.encode(byteBuffer, sequenceNumber);
        VarLenInt.encode(byteBuffer, retirePriorTo);
        VarLenInt.encode(byteBuffer, connectionId.length);
        BufferUtil.flipToFlush(byteBuffer, position);

        accumulator.append(RetainableByteBuffer.wrap(ByteBuffer.wrap(connectionId)));
        byte[] resetToken = frame.getResetToken();
        accumulator.append(RetainableByteBuffer.wrap(ByteBuffer.wrap(resetToken)));

        return capacity + connectionId.length + resetToken.length;
    }

    private long generateRetireConnectionIdFrame(ByteBufferPool.Accumulator accumulator, RetireConnectionIdFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long sequenceNumber = frame.getSequenceNumber();
        capacity += VarLenInt.length(sequenceNumber);

        RetainableByteBuffer buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.append(buffer);
        ByteBuffer byteBuffer = buffer.getByteBuffer();

        int position = BufferUtil.flipToFill(byteBuffer);
        VarLenInt.encode(byteBuffer, frameType);
        VarLenInt.encode(byteBuffer, sequenceNumber);
        BufferUtil.flipToFlush(byteBuffer, position);

        return capacity;
    }

    private long generatePathChallengeFrame(ByteBufferPool.Accumulator accumulator, PathChallengeFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long data = frame.getData();
        capacity += 8;

        RetainableByteBuffer buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.append(buffer);
        ByteBuffer byteBuffer = buffer.getByteBuffer();

        int position = BufferUtil.flipToFill(byteBuffer);
        VarLenInt.encode(byteBuffer, frameType);
        byteBuffer.putLong(data);
        BufferUtil.flipToFlush(byteBuffer, position);

        return capacity;
    }

    private long generatePathResponseFrame(ByteBufferPool.Accumulator accumulator, PathResponseFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long data = frame.getData();
        capacity += 8;

        RetainableByteBuffer buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.append(buffer);
        ByteBuffer byteBuffer = buffer.getByteBuffer();

        int position = BufferUtil.flipToFill(byteBuffer);
        VarLenInt.encode(byteBuffer, frameType);
        byteBuffer.putLong(data);
        BufferUtil.flipToFlush(byteBuffer, position);

        return capacity;
    }

    private long generateConnectionCloseFrame(ByteBufferPool.Accumulator accumulator, ConnectionCloseFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long errorCode = frame.getErrorCode();
        capacity += VarLenInt.length(errorCode);
        long causeFrameType = frame.getCauseFrameType();
        if (frameType == 0x1C)
            capacity += VarLenInt.length(causeFrameType);
        String reason = frame.getReason();
        ByteBuffer reasonBytes = StandardCharsets.UTF_8.encode(reason);
        int reasonLength = reasonBytes.remaining();
        capacity += VarLenInt.length(reasonLength);

        RetainableByteBuffer buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.append(buffer);
        ByteBuffer byteBuffer = buffer.getByteBuffer();

        int position = BufferUtil.flipToFill(byteBuffer);
        VarLenInt.encode(byteBuffer, frameType);
        VarLenInt.encode(byteBuffer, errorCode);
        if (frameType == 0x1C)
            VarLenInt.encode(byteBuffer, causeFrameType);
        VarLenInt.encode(byteBuffer, reasonLength);
        BufferUtil.flipToFlush(byteBuffer, position);

        accumulator.append(RetainableByteBuffer.wrap(reasonBytes));

        return capacity + reasonLength;
    }

    public record BytesGenerated(int dataBytes, int frameBytes)
    {
    }
}
