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
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.api.frames.Frame;
import org.eclipse.jetty.quic.api.frames.StreamFrame;
import org.eclipse.jetty.quic.common.internal.frames.ConnectionCloseParser;
import org.eclipse.jetty.quic.common.internal.frames.CryptoFrameParser;
import org.eclipse.jetty.quic.common.internal.frames.DataBlockedParser;
import org.eclipse.jetty.quic.common.internal.frames.FrameParser;
import org.eclipse.jetty.quic.common.internal.frames.MaxDataParser;
import org.eclipse.jetty.quic.common.internal.frames.MaxStreamsParser;
import org.eclipse.jetty.quic.common.internal.frames.PaddingFrameParser;
import org.eclipse.jetty.quic.common.internal.frames.ResetStreamParser;
import org.eclipse.jetty.quic.common.internal.frames.StopSendingParser;
import org.eclipse.jetty.quic.common.internal.frames.StreamDataBlockedParser;
import org.eclipse.jetty.quic.common.internal.frames.StreamMaxDataParser;
import org.eclipse.jetty.quic.common.internal.frames.StreamParser;
import org.eclipse.jetty.quic.common.internal.frames.StreamsBlockedParser;
import org.eclipse.jetty.quic.util.ErrorCode;
import org.eclipse.jetty.quic.util.QuicException;
import org.eclipse.jetty.quic.util.VarLenInt;

public class FramesParser
{
    private final Map<FrameType, FrameParser> parsers = new EnumMap<>(FrameType.class);
    private State state = State.FRAME_TYPE;
    private int type;

    public FramesParser()
    {
        VarLenInt varLenInt = new VarLenInt();
        parsers.put(FrameType.PADDING, new PaddingFrameParser());
        parsers.put(FrameType.PING, null/*TODO*/);
        parsers.put(FrameType.ACK, null/*TODO*/);
        parsers.put(FrameType.RESET_STREAM, new ResetStreamParser(varLenInt));
        parsers.put(FrameType.STOP_SENDING, new StopSendingParser(varLenInt));
        parsers.put(FrameType.CRYPTO, new CryptoFrameParser(varLenInt));
        parsers.put(FrameType.NEW_TOKEN, null/*TODO*/);
        parsers.put(FrameType.STREAM, new StreamParser(varLenInt));
        parsers.put(FrameType.MAX_DATA, new MaxDataParser(varLenInt));
        parsers.put(FrameType.STREAM_MAX_DATA, new StreamMaxDataParser(varLenInt));
        parsers.put(FrameType.MAX_STREAMS, new MaxStreamsParser(varLenInt));
        parsers.put(FrameType.DATA_BLOCKED, new DataBlockedParser(varLenInt));
        parsers.put(FrameType.STREAM_DATA_BLOCKED, new StreamDataBlockedParser(varLenInt));
        parsers.put(FrameType.STREAMS_BLOCKED, new StreamsBlockedParser(varLenInt));
        parsers.put(FrameType.NEW_CONNECTION_ID, null/*TODO*/);
        parsers.put(FrameType.RETIRE_CONNECTION_ID, null/*TODO*/);
        parsers.put(FrameType.PATH_CHALLENGE, null/*TODO*/);
        parsers.put(FrameType.PATH_RESPONSE, null/*TODO*/);
        parsers.put(FrameType.CONNECTION_CLOSE, new ConnectionCloseParser(varLenInt));
        parsers.put(FrameType.HANDSHAKE_DONE, null/*TODO*/);
    }

    public int getFrameMaxSize()
    {
        StreamParser parser = (StreamParser)parsers.get(FrameType.STREAM);
        return parser.getFrameMaxSize();
    }

    public void setFrameMaxSize(int maxSize)
    {
        StreamParser parser = (StreamParser)parsers.get(FrameType.STREAM);
        parser.setFrameMaxSize(maxSize);
    }

    public int getConnectionCloseReasonMaxLength()
    {
        ConnectionCloseParser parser = (ConnectionCloseParser)parsers.get(FrameType.CONNECTION_CLOSE);
        return parser.getReasonMaxLength();
    }

    public void setConnectionCloseReasonMaxLength(int maxLength)
    {
        ConnectionCloseParser parser = (ConnectionCloseParser)parsers.get(FrameType.CONNECTION_CLOSE);
        parser.setReasonMaxLength(maxLength);
    }

    public Frame parse(RetainableByteBuffer buffer)
    {
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        while (byteBuffer.hasRemaining())
        {
            switch (state)
            {
                case FRAME_TYPE ->
                {
                    type = byteBuffer.get() & 0xFF;
                    state = State.FRAME_BODY;
                }
                case FRAME_BODY ->
                {
                    FrameType frameType = FrameType.from(type);
                    Frame frame = parseFrame(buffer, frameType, type);

                    // Not enough bytes for a full frame.
                    // This may be the case for QUIC-STREAMS.
                    if (frame == null)
                        return null;

                    // Support for synthetic StreamFrame for QUIC-STREAMS.
                    boolean synthetic = frame instanceof StreamFrame streamFrame && !streamFrame.isEndData();
                    if (synthetic)
                    {
                        // Stay in the current state, waiting
                        // for the remainder of the frame bytes.
                        return frame;
                    }

                    state = State.FRAME_TYPE;
                    return frame;
                }
            }
        }
        return null;
    }

    public List<Frame> consume(RetainableByteBuffer buffer)
    {
        List<Frame> result = new ArrayList<>();
        while (buffer.hasRemaining())
        {
            Frame frame = parse(buffer);
            if (frame == null)
                throw new IllegalStateException("insufficient bytes to parse a frame");
            // Drop PADDING frames.
            if (frame.getFrameType() != 0)
                result.add(frame);
        }
        return result;
    }

    private Frame parseFrame(RetainableByteBuffer buffer, FrameType frameType, int type)
    {
        if (frameType != null)
            return parsers.get(frameType).parse(buffer);
        else
            return parseUnknownFrame(buffer, type);
    }

    protected Frame parseUnknownFrame(RetainableByteBuffer buffer, int frameType)
    {
        throw new QuicException(ErrorCode.FRAME_ENCODING_ERROR, "invalid_quic_frame_type", frameType);
    }

    private enum State
    {
        FRAME_TYPE, FRAME_BODY
    }
}
