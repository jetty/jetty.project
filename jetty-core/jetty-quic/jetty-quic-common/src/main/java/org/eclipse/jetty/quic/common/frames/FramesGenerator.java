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

    public void generate(RetainableByteBuffer.Mutable accumulator, Frame frame)
    {
        long type = frame.type();
        FrameType frameType = FrameType.from(type);
        if (frameType == null)
            throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_frame_type", type);
        switch (frameType)
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

    public void generate(RetainableByteBuffer.Mutable accumulator, StreamFrame frame, int maxDataBytes, int maxFrameBytes)
    {
        generateStreamFrame(accumulator, frame, maxDataBytes, maxFrameBytes);
    }

    private void generateNoContentFrame(RetainableByteBuffer.Mutable accumulator, Frame frame)
    {
        VarLenInt.encode(accumulator, frame.type());
    }

    private void generateAckFrame(RetainableByteBuffer.Mutable accumulator, AckFrame frame)
    {
        VarLenInt.encode(accumulator, frame.type());
        VarLenInt.encode(accumulator, frame.largestAcknowledged());
        VarLenInt.encode(accumulator, frame.ackDelay());
        VarLenInt.encode(accumulator, frame.firstRangeLength());
        List<AckFrame.AckRange> ranges = frame.ackRanges();
        VarLenInt.encode(accumulator, ranges.size());
        for (AckFrame.AckRange range : ranges)
        {
            VarLenInt.encode(accumulator, range.gap());
            VarLenInt.encode(accumulator, range.length());
        }
    }

    private void generateResetStreamFrame(RetainableByteBuffer.Mutable accumulator, ResetFrame frame)
    {
        VarLenInt.encode(accumulator, frame.type());
        VarLenInt.encode(accumulator, frame.streamId());
        VarLenInt.encode(accumulator, frame.applicationErrorCode());
        VarLenInt.encode(accumulator, frame.finalSize());
    }

    private void generateStopSendingFrame(RetainableByteBuffer.Mutable accumulator, StopSendingFrame frame)
    {
        VarLenInt.encode(accumulator, frame.type());
        VarLenInt.encode(accumulator, frame.streamId());
        VarLenInt.encode(accumulator, frame.applicationErrorCode());
    }

    private void generateCryptoFrame(RetainableByteBuffer.Mutable accumulator, CryptoFrame frame)
    {
        VarLenInt.encode(accumulator, frame.type());
        VarLenInt.encode(accumulator, frame.offset());
        RetainableByteBuffer data = frame.data();
        VarLenInt.encode(accumulator, data.remaining());
        accumulator.append(data);
    }

    private void generateNewTokenFrame(RetainableByteBuffer.Mutable accumulator, NewTokenFrame frame)
    {
        VarLenInt.encode(accumulator, frame.type());
        byte[] token = frame.token();
        VarLenInt.encode(accumulator, token.length);
        accumulator.append(ByteBuffer.wrap(token));
    }

    private void generateStreamFrame(RetainableByteBuffer.Mutable accumulator, StreamFrame frame, int maxDataBytes, int maxFrameBytes)
    {
        // TODO: review this logic about truncating the data to maxDataBytes.

        long frameType = frame.type();
        int capacity = VarLenInt.length(frameType);
        long streamId = frame.streamId();
        capacity += VarLenInt.length(streamId);
        long offset = frame.offset();
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
        RetainableByteBuffer data = frame.data();
        boolean dataExceedsFrame = data.remaining() > dataLength;
        if (endStream && dataExceedsFrame)
            frameType = frameType & ~StreamFrame.END_STREAM_MASK;

        VarLenInt.encode(accumulator, frameType);
        VarLenInt.encode(accumulator, streamId);
        if (hasOffset)
            VarLenInt.encode(accumulator, offset);
        if (hasLength)
            VarLenInt.encode(accumulator, dataLength);

        if (dataExceedsFrame)
        {
            RetainableByteBuffer slice = data.slice(dataLength);
            data.skip(dataLength);
            data = slice;
        }

        accumulator.append(data);
    }

    private void generateMaxDataFrame(RetainableByteBuffer.Mutable accumulator, MaxDataFrame frame)
    {
        VarLenInt.encode(accumulator, frame.type());
        VarLenInt.encode(accumulator, frame.maxData());
    }

    private void generateStreamMaxDataFrame(RetainableByteBuffer.Mutable accumulator, StreamMaxDataFrame frame)
    {
        VarLenInt.encode(accumulator, frame.type());
        VarLenInt.encode(accumulator, frame.streamId());
        VarLenInt.encode(accumulator, frame.maxData());
    }

    private void generateMaxStreamsFrame(RetainableByteBuffer.Mutable accumulator, MaxStreamsFrame frame)
    {
        VarLenInt.encode(accumulator, frame.type());
        VarLenInt.encode(accumulator, frame.maxStreams());
    }

    private void generateDataBlockedFrame(RetainableByteBuffer.Mutable accumulator, DataBlockedFrame frame)
    {
        VarLenInt.encode(accumulator, frame.type());
        VarLenInt.encode(accumulator, frame.offset());
    }

    private void generateStreamDataBlockedFrame(RetainableByteBuffer.Mutable accumulator, StreamDataBlockedFrame frame)
    {
        VarLenInt.encode(accumulator, frame.type());
        VarLenInt.encode(accumulator, frame.streamId());
        VarLenInt.encode(accumulator, frame.offset());
    }

    private void generateStreamsBlockedFrame(RetainableByteBuffer.Mutable accumulator, StreamsBlockedFrame frame)
    {
        VarLenInt.encode(accumulator, frame.type());
        VarLenInt.encode(accumulator, frame.maxStreams());
    }

    private void generateNewConnectionIdFrame(RetainableByteBuffer.Mutable accumulator, NewConnectionIdFrame frame)
    {
        VarLenInt.encode(accumulator, frame.type());
        VarLenInt.encode(accumulator, frame.sequenceNumber());
        VarLenInt.encode(accumulator, frame.retirePriorTo());
        byte[] connectionId = frame.connectionId();
        VarLenInt.encode(accumulator, connectionId.length);
        accumulator.append(ByteBuffer.wrap(connectionId));
        byte[] resetToken = frame.resetToken();
        accumulator.append(ByteBuffer.wrap(resetToken));
    }

    private void generateRetireConnectionIdFrame(RetainableByteBuffer.Mutable accumulator, RetireConnectionIdFrame frame)
    {
        VarLenInt.encode(accumulator, frame.type());
        VarLenInt.encode(accumulator, frame.sequenceNumber());
    }

    private void generatePathChallengeFrame(RetainableByteBuffer.Mutable accumulator, PathChallengeFrame frame)
    {
        VarLenInt.encode(accumulator, frame.type());
        accumulator.putLong(frame.data());
    }

    private void generatePathResponseFrame(RetainableByteBuffer.Mutable accumulator, PathResponseFrame frame)
    {
        VarLenInt.encode(accumulator, frame.type());
        accumulator.putLong(frame.data());
    }

    private void generateConnectionCloseFrame(RetainableByteBuffer.Mutable accumulator, ConnectionCloseFrame frame)
    {
        VarLenInt.encode(accumulator, frame.type());
        VarLenInt.encode(accumulator, frame.errorCode());
        if (frame.type() == 0x1C)
            VarLenInt.encode(accumulator, frame.causeFrameType());
        String reason = frame.reason();
        ByteBuffer reasonBytes = StandardCharsets.UTF_8.encode(reason);
        VarLenInt.encode(accumulator, reasonBytes.remaining());
        accumulator.append(RetainableByteBuffer.wrap(reasonBytes));
    }
}
