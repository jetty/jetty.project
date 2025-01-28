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

    public Frame parse(ByteBuffer byteBuffer)
    {
        while (byteBuffer.hasRemaining())
        {
            switch (state)
            {
                case FRAME_TYPE ->
                {
                    frameType = byteBuffer.get(byteBuffer.position()) & 0xFF;
                    state = State.FRAME_BODY;
                }
                case FRAME_BODY ->
                {
                    Frame frame;
                    FrameType type = FrameType.from(frameType);
                    if (type != null)
                        frame = parseFrame(byteBuffer, type, frameType);
                    else
                        frame = parseUnknownFrame(byteBuffer, frameType);
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

    protected Frame parseFrame(ByteBuffer byteBuffer, FrameType frameType, int type)
    {
        return switch (frameType)
        {
            case PADDING -> new Frame(byteBuffer.get());
            case RESET_STREAM -> parseResetStream(byteBuffer);
            case STOP_SENDING -> parseStopSending(byteBuffer);
            case STREAM -> parseStream(byteBuffer);
            case MAX_DATA -> parseMaxData(byteBuffer);
            case STREAM_MAX_DATA -> parseStreamMaxData(byteBuffer);
            case MAX_STREAMS -> parseMaxStreams(byteBuffer);
            case DATA_BLOCKED -> parseDataBlocked(byteBuffer);
            case STREAM_DATA_BLOCKED -> parseStreamDataBlocked(byteBuffer);
            case STREAMS_BLOCKED -> parseStreamsBlocked(byteBuffer);
            case CONNECTION_CLOSE -> parseConnectionClose(byteBuffer);
            default -> throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "unsupported_quic_frame_type", type);
        };
    }

    protected Frame parseUnknownFrame(ByteBuffer byteBuffer, int frameType)
    {
        throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_quic_frame_type", frameType);
    }

    protected Frame parseResetStream(ByteBuffer byteBuffer)
    {
        return resetStreamParser.parse(byteBuffer);
    }

    protected Frame parseStopSending(ByteBuffer byteBuffer)
    {
        return stopSendingParser.parse(byteBuffer);
    }

    protected Frame parseStream(ByteBuffer byteBuffer)
    {
        return streamParser.parse(byteBuffer);
    }

    protected Frame parseMaxData(ByteBuffer byteBuffer)
    {
        return maxDataParser.parse(byteBuffer);
    }

    protected Frame parseStreamMaxData(ByteBuffer byteBuffer)
    {
        return streamMaxDataParser.parse(byteBuffer);
    }

    protected Frame parseMaxStreams(ByteBuffer byteBuffer)
    {
        return maxStreamsParser.parse(byteBuffer);
    }

    protected Frame parseDataBlocked(ByteBuffer byteBuffer)
    {
        return dataBlockedParser.parse(byteBuffer);
    }

    protected Frame parseStreamDataBlocked(ByteBuffer byteBuffer)
    {
        return streamDataBlockedParser.parse(byteBuffer);
    }

    protected Frame parseStreamsBlocked(ByteBuffer byteBuffer)
    {
        return streamsBlockedParser.parse(byteBuffer);
    }

    protected Frame parseConnectionClose(ByteBuffer byteBuffer)
    {
        return connectionCloseParser.parse(byteBuffer);
    }

    private enum State
    {
        FRAME_TYPE, FRAME_BODY
    }
}
