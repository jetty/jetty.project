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
import org.eclipse.jetty.io.WritableBufferPool;
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
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class FrameGenerator
{
    private final WritableBufferPool byteBufferPool;
    private boolean useDirectBuffers;

    public FrameGenerator(ByteBufferPool byteBufferPool)
    {
        this.byteBufferPool = WritableBufferPool.wrap(byteBufferPool);
        setUseDirectBuffers(true);
    }

    public WritableBufferPool getByteBufferPool()
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

    public long generate(List<RetainableByteBuffer> accumulator, Frame frame)
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

    public BytesGenerated generate(List<RetainableByteBuffer> accumulator, StreamFrame frame, int maxDataBytes, int maxFrameBytes)
    {
        return generateStreamFrame(accumulator, frame, maxDataBytes, maxFrameBytes);
    }

    private long generateNoContentFrame(List<RetainableByteBuffer> accumulator, Frame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);

        RetainableByteBuffer.Mutable buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.add(buffer);

        VarLenInt.encode(buffer, frameType);

        return capacity;
    }

    private long generateAckFrame(List<RetainableByteBuffer> accumulator, AckFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long ackNumber = frame.getAckNumber();
        capacity += VarLenInt.length(ackNumber);
        long ackDelay = frame.getAckDelay();
        capacity += VarLenInt.length(ackDelay);
        List<Integer> ranges = frame.getRanges();
        int rangeSize = ranges.size();
        capacity += VarLenInt.length(rangeSize - 1);
        for (Integer range : ranges)
        {
            capacity += VarLenInt.length(range);
        }
        if (frameType == 0x03)
        {
            capacity += VarLenInt.length(frame.getECT0Count()) +
                VarLenInt.length(frame.getECT1Count()) +
                VarLenInt.length(frame.getCECount());
        }

        RetainableByteBuffer.Mutable buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.add(buffer);

        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, ackNumber);
        VarLenInt.encode(buffer, ackDelay);
        VarLenInt.encode(buffer, rangeSize - 1);
        for (Integer range : ranges)
        {
            VarLenInt.encode(buffer, range);
        }
        if (frameType == 0x03)
        {
            VarLenInt.encode(buffer, frame.getECT0Count());
            VarLenInt.encode(buffer, frame.getECT1Count());
            VarLenInt.encode(buffer, frame.getCECount());
        }

        return capacity;
    }

    private long generateResetStreamFrame(List<RetainableByteBuffer> accumulator, ResetFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long streamId = frame.getStreamId();
        capacity += VarLenInt.length(streamId);
        long errorCode = frame.getApplicationErrorCode();
        capacity += VarLenInt.length(errorCode);
        long finalSize = frame.getFinalSize();
        capacity += VarLenInt.length(finalSize);

        RetainableByteBuffer.Mutable buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.add(buffer);

        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, streamId);
        VarLenInt.encode(buffer, errorCode);
        VarLenInt.encode(buffer, finalSize);

        return capacity;
    }

    private long generateStopSendingFrame(List<RetainableByteBuffer> accumulator, StopSendingFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long streamId = frame.getStreamId();
        capacity += VarLenInt.length(streamId);
        long errorCode = frame.getApplicationErrorCode();
        capacity += VarLenInt.length(errorCode);

        RetainableByteBuffer.Mutable buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.add(buffer);

        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, streamId);
        VarLenInt.encode(buffer, errorCode);

        return capacity;
    }

    private long generateCryptoFrame(List<RetainableByteBuffer> accumulator, CryptoFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long offset = frame.getOffset();
        capacity += VarLenInt.length(offset);
        ByteBuffer data = frame.getData();
        int length = data.remaining();
        capacity += VarLenInt.length(length);

        RetainableByteBuffer.Mutable buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.add(buffer);

        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, offset);
        VarLenInt.encode(buffer, length);

        accumulator.add(RetainableByteBuffer.wrap(data));

        return capacity + length;
    }

    private long generateNewTokenFrame(List<RetainableByteBuffer> accumulator, NewTokenFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        ByteBuffer token = frame.getToken();
        int length = token.remaining();
        capacity += VarLenInt.length(length);

        RetainableByteBuffer.Mutable buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.add(buffer);

        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, length);

        accumulator.add(RetainableByteBuffer.wrap(token));

        return capacity + length;
    }

    private BytesGenerated generateStreamFrame(List<RetainableByteBuffer> accumulator, StreamFrame frame, int maxDataBytes, int maxFrameBytes)
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
        RetainableByteBuffer data = frame.acquire();
        boolean dataExceedsFrame = data.remaining() > dataLength;
        if (endStream && dataExceedsFrame)
            frameType = frameType & ~StreamFrame.END_STREAM_MASK;

        RetainableByteBuffer.Mutable buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.add(buffer);

        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, streamId);
        if (hasOffset)
            VarLenInt.encode(buffer, offset);
        if (hasLength)
            VarLenInt.encode(buffer, dataLength);

        if (dataExceedsFrame)
        {
            RetainableByteBuffer slice = data.sliceAndConsume(dataLength);
            data.release();
            data = slice;
        }
        accumulator.add(data);

        return new BytesGenerated(dataLength, capacity + dataLength);
    }

    private long generateMaxDataFrame(List<RetainableByteBuffer> accumulator, MaxDataFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long maxData = frame.getMaxData();
        capacity += VarLenInt.length(maxData);

        RetainableByteBuffer.Mutable buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.add(buffer);

        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, maxData);

        return capacity;
    }

    private long generateStreamMaxDataFrame(List<RetainableByteBuffer> accumulator, StreamMaxDataFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long streamId = frame.getStreamId();
        capacity += VarLenInt.length(streamId);
        long maxData = frame.getMaxData();
        capacity += VarLenInt.length(maxData);

        RetainableByteBuffer.Mutable buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.add(buffer);

        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, streamId);
        VarLenInt.encode(buffer, maxData);

        return capacity;
    }

    private long generateMaxStreamsFrame(List<RetainableByteBuffer> accumulator, MaxStreamsFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long maxStreams = frame.getMaxStreams();
        capacity += VarLenInt.length(maxStreams);

        RetainableByteBuffer.Mutable buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.add(buffer);

        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, maxStreams);

        return capacity;
    }

    private long generateDataBlockedFrame(List<RetainableByteBuffer> accumulator, DataBlockedFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long maxData = frame.getOffset();
        capacity += VarLenInt.length(maxData);

        RetainableByteBuffer.Mutable buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.add(buffer);

        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, maxData);

        return capacity;
    }

    private long generateStreamDataBlockedFrame(List<RetainableByteBuffer> accumulator, StreamDataBlockedFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long streamId = frame.getStreamId();
        capacity += VarLenInt.length(streamId);
        long maxData = frame.getOffset();
        capacity += VarLenInt.length(maxData);

        RetainableByteBuffer.Mutable buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.add(buffer);

        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, streamId);
        VarLenInt.encode(buffer, maxData);

        return capacity;
    }

    private long generateStreamsBlockedFrame(List<RetainableByteBuffer> accumulator, StreamsBlockedFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long maxStreams = frame.getMaxStreams();
        capacity += VarLenInt.length(maxStreams);

        RetainableByteBuffer.Mutable buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.add(buffer);

        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, maxStreams);

        return capacity;
    }

    private long generateNewConnectionIdFrame(List<RetainableByteBuffer> accumulator, NewConnectionIdFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long sequenceNumber = frame.getSequenceNumber();
        capacity += VarLenInt.length(sequenceNumber);
        long retirePriorTo = frame.getRetirePriorTo();
        capacity += VarLenInt.length(retirePriorTo);
        byte[] connectionId = frame.getConnectionId();
        capacity += VarLenInt.length(connectionId.length);

        RetainableByteBuffer.Mutable buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.add(buffer);

        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, sequenceNumber);
        VarLenInt.encode(buffer, retirePriorTo);
        VarLenInt.encode(buffer, connectionId.length);

        accumulator.add(RetainableByteBuffer.wrap(ByteBuffer.wrap(connectionId)));
        byte[] resetToken = frame.getResetToken();
        accumulator.add(RetainableByteBuffer.wrap(ByteBuffer.wrap(resetToken)));

        return capacity + connectionId.length + resetToken.length;
    }

    private long generateRetireConnectionIdFrame(List<RetainableByteBuffer> accumulator, RetireConnectionIdFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long sequenceNumber = frame.getSequenceNumber();
        capacity += VarLenInt.length(sequenceNumber);

        RetainableByteBuffer.Mutable buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.add(buffer);

        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, sequenceNumber);

        return capacity;
    }

    private long generatePathChallengeFrame(List<RetainableByteBuffer> accumulator, PathChallengeFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long data = frame.getData();
        capacity += 8;

        RetainableByteBuffer.Mutable buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.add(buffer);

        VarLenInt.encode(buffer, frameType);
        buffer.putLong(data);

        return capacity;
    }

    private long generatePathResponseFrame(List<RetainableByteBuffer> accumulator, PathResponseFrame frame)
    {
        long frameType = frame.getFrameType();
        int capacity = VarLenInt.length(frameType);
        long data = frame.getData();
        capacity += 8;

        RetainableByteBuffer.Mutable buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.add(buffer);

        VarLenInt.encode(buffer, frameType);
        buffer.putLong(data);

        return capacity;
    }

    private long generateConnectionCloseFrame(List<RetainableByteBuffer> accumulator, ConnectionCloseFrame frame)
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

        RetainableByteBuffer.Mutable buffer = byteBufferPool.acquire(capacity, isUseDirectBuffers());
        accumulator.add(buffer);

        VarLenInt.encode(buffer, frameType);
        VarLenInt.encode(buffer, errorCode);
        if (frameType == 0x1C)
            VarLenInt.encode(buffer, causeFrameType);
        VarLenInt.encode(buffer, reasonLength);

        accumulator.add(RetainableByteBuffer.wrap(reasonBytes));

        return capacity + reasonLength;
    }

    public record BytesGenerated(int dataBytes, int frameBytes)
    {
    }
}
