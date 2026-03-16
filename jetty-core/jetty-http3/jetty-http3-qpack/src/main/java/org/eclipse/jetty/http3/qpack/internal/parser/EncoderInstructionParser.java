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

package org.eclipse.jetty.http3.qpack.internal.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.compression.NBitIntegerDecoder;
import org.eclipse.jetty.http3.qpack.QpackException;
import org.eclipse.jetty.util.BufferUtil;

/**
 * Parses a stream of unframed instructions for the Encoder. These instructions are sent from the remote Decoder.
 */
public class EncoderInstructionParser
{
    private static final int SECTION_ACKNOWLEDGEMENT_PREFIX = 7;
    private static final int STREAM_CANCELLATION_PREFIX = 6;
    private static final int INSERT_COUNT_INCREMENT_PREFIX = 6;

    private final Handler _handler;
    private final NBitIntegerDecoder _integerDecoder;
    private State _state = State.IDLE;

    private enum State
    {
        IDLE,
        SECTION_ACKNOWLEDGEMENT,
        STREAM_CANCELLATION,
        INSERT_COUNT_INCREMENT
    }

    public interface Handler
    {
        void onSectionAcknowledgement(long streamId) throws QpackException;

        void onStreamCancellation(long streamId);

        void onInsertCountIncrement(int increment) throws QpackException;
    }

    public EncoderInstructionParser(Handler handler)
    {
        _handler = handler;
        _integerDecoder = new NBitIntegerDecoder();
    }

    /**
     * This will parse and fully consume the given {@link ByteBuffer} and notifies
     * the {@link Handler} supplied in the constructor for any resulting events.
     * @param buffer the buffer to parse.
     * @throws QpackException if there was an error parsing the instructions.
     */
    public void parse(ByteBuffer buffer) throws QpackException
    {
        while (BufferUtil.hasContent(buffer))
        {
            switch (_state)
            {
                case IDLE ->
                {
                    // Get first byte without incrementing the buffers position.
                    byte firstByte = buffer.get(buffer.position());
                    if ((firstByte & 0x80) != 0)
                    {
                        _state = State.SECTION_ACKNOWLEDGEMENT;
                        _integerDecoder.setPrefix(SECTION_ACKNOWLEDGEMENT_PREFIX);
                    }
                    else if ((firstByte & 0x40) != 0)
                    {
                        _state = State.STREAM_CANCELLATION;
                        _integerDecoder.setPrefix(STREAM_CANCELLATION_PREFIX);
                    }
                    else
                    {
                        _state = State.INSERT_COUNT_INCREMENT;
                        _integerDecoder.setPrefix(INSERT_COUNT_INCREMENT_PREFIX);
                    }
                }
                case SECTION_ACKNOWLEDGEMENT -> parseSectionAcknowledgment(buffer);
                case STREAM_CANCELLATION -> parseStreamCancellation(buffer);
                case INSERT_COUNT_INCREMENT -> parseInsertCountIncrement(buffer);
                default -> throw new IllegalStateException(_state.name());
            }
        }
    }

    private void parseSectionAcknowledgment(ByteBuffer buffer) throws QpackException
    {
        long streamId = _integerDecoder.decodeInt(buffer);
        if (streamId >= 0)
        {
            reset();
            _handler.onSectionAcknowledgement(streamId);
        }
    }

    private void parseStreamCancellation(ByteBuffer buffer) throws QpackException
    {
        long streamId = _integerDecoder.decodeLong(buffer);
        if (streamId >= 0)
        {
            reset();
            _handler.onStreamCancellation(streamId);
        }
    }

    private void parseInsertCountIncrement(ByteBuffer buffer) throws QpackException
    {
        int increment = _integerDecoder.decodeInt(buffer);
        if (increment >= 0)
        {
            reset();
            _handler.onInsertCountIncrement(increment);
        }
    }

    public void reset()
    {
        _state = State.IDLE;
        _integerDecoder.reset();
    }
}
