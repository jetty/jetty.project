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

import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.common.internal.frames.ConnectionCloseParser;
import org.eclipse.jetty.quic.common.internal.frames.DataBlockedParser;
import org.eclipse.jetty.quic.common.internal.frames.MaxDataParser;
import org.eclipse.jetty.quic.common.internal.frames.MaxStreamsParser;
import org.eclipse.jetty.quic.common.internal.frames.ResetStreamParser;
import org.eclipse.jetty.quic.common.internal.frames.StopSendingParser;
import org.eclipse.jetty.quic.common.internal.frames.StreamDataBlockedParser;
import org.eclipse.jetty.quic.common.internal.frames.StreamMaxDataParser;
import org.eclipse.jetty.quic.common.internal.frames.StreamParser;
import org.eclipse.jetty.quic.common.internal.frames.StreamsBlockedParser;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.quic.util.QuicException;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class FrameParser
{
    private final VarLenInt varLenInt = new VarLenInt();
    private final ResetStreamParser resetStreamParser = new ResetStreamParser(varLenInt);
    private final StopSendingParser stopSendingParser = new StopSendingParser(varLenInt);
    private final StreamParser streamParser = new StreamParser(varLenInt);
    private final MaxDataParser maxDataParser = new MaxDataParser(varLenInt);
    private final StreamMaxDataParser streamMaxDataParser = new StreamMaxDataParser(varLenInt);
    private final MaxStreamsParser maxStreamsParser = new MaxStreamsParser(varLenInt);
    private final DataBlockedParser dataBlockedParser = new DataBlockedParser(varLenInt);
    private final StreamDataBlockedParser streamDataBlockedParser = new StreamDataBlockedParser(varLenInt);
    private final StreamsBlockedParser streamsBlockedParser = new StreamsBlockedParser(varLenInt);
    private final ConnectionCloseParser connectionCloseParser = new ConnectionCloseParser(varLenInt);
    private State state = State.FRAME_TYPE;
    private int frameType;

    public VarLenInt getVarLenInt()
    {
        return varLenInt;
    }

    public int getFrameMaxSize()
    {
        return streamParser.getFrameMaxSize();
    }

    public void setFrameMaxSize(int maxSize)
    {
        streamParser.setFrameMaxSize(maxSize);
    }

    public void setConnectionCloseReasonMaxLength(int maxLength)
    {
        connectionCloseParser.setReasonMaxLength(maxLength);
    }

    public Frame parse(RetainableByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case FRAME_TYPE ->
                {
                    frameType = buffer.getByteAsInt(buffer.readPosition());
                    state = State.FRAME_BODY;
                }
                case FRAME_BODY ->
                {
                    Frame frame;
                    FrameType type = FrameType.from(frameType);
                    if (type != null)
                        frame = parseFrame(buffer, type, frameType);
                    else
                        frame = parseUnknownFrame(buffer, frameType);
                    if (frame == null)
                        return null;
                    if (!(frame instanceof StreamFrame streamFrame) || streamFrame.isEndData())
                        state = State.FRAME_TYPE;
                    return frame;
                }
            }
        }
        return null;
    }

    protected Frame parseFrame(RetainableByteBuffer buffer, FrameType frameType, int type)
    {
        return switch (frameType)
        {
            case PADDING -> new Frame(buffer.get());
            case RESET_STREAM -> parseResetStream(buffer);
            case STOP_SENDING -> parseStopSending(buffer);
            case STREAM -> parseStream(buffer);
            case MAX_DATA -> parseMaxData(buffer);
            case STREAM_MAX_DATA -> parseStreamMaxData(buffer);
            case MAX_STREAMS -> parseMaxStreams(buffer);
            case DATA_BLOCKED -> parseDataBlocked(buffer);
            case STREAM_DATA_BLOCKED -> parseStreamDataBlocked(buffer);
            case STREAMS_BLOCKED -> parseStreamsBlocked(buffer);
            case CONNECTION_CLOSE -> parseConnectionClose(buffer);
            default -> throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "unsupported_quic_frame_type", type);
        };
    }

    protected Frame parseUnknownFrame(RetainableByteBuffer buffer, int frameType)
    {
        throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_quic_frame_type", frameType);
    }

    protected Frame parseResetStream(RetainableByteBuffer buffer)
    {
        return resetStreamParser.parse(buffer);
    }

    protected Frame parseStopSending(RetainableByteBuffer buffer)
    {
        return stopSendingParser.parse(buffer);
    }

    protected Frame parseStream(RetainableByteBuffer buffer)
    {
        return streamParser.parse(buffer);
    }

    protected Frame parseMaxData(RetainableByteBuffer buffer)
    {
        return maxDataParser.parse(buffer);
    }

    protected Frame parseStreamMaxData(RetainableByteBuffer buffer)
    {
        return streamMaxDataParser.parse(buffer);
    }

    protected Frame parseMaxStreams(RetainableByteBuffer buffer)
    {
        return maxStreamsParser.parse(buffer);
    }

    protected Frame parseDataBlocked(RetainableByteBuffer buffer)
    {
        return dataBlockedParser.parse(buffer);
    }

    protected Frame parseStreamDataBlocked(RetainableByteBuffer buffer)
    {
        return streamDataBlockedParser.parse(buffer);
    }

    protected Frame parseStreamsBlocked(RetainableByteBuffer buffer)
    {
        return streamsBlockedParser.parse(buffer);
    }

    protected Frame parseConnectionClose(RetainableByteBuffer buffer)
    {
        return connectionCloseParser.parse(buffer);
    }

    private enum State
    {
        FRAME_TYPE, FRAME_BODY
    }
}
