//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.qpack.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.qpack.QpackEncoder;
import org.eclipse.jetty.http3.qpack.QpackException;

/**
 * Parses a stream of unframed instructions for the Encoder. These instructions are sent from the remote Decoder.
 */
public class EncoderInstructionParser
{
    private static final int SECTION_ACKNOWLEDGEMENT_PREFIX = 7;
    private static final int STREAM_CANCELLATION_PREFIX = 6;
    private static final int INSERT_COUNT_INCREMENT_PREFIX = 6;

    private final Handler _handler;
    private final NBitIntegerParser _integerParser;
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
        void onSectionAcknowledgement(int streamId) throws QpackException;

        void onStreamCancellation(int streamId) throws QpackException;

        void onInsertCountIncrement(int increment) throws QpackException;
    }

    public static class EncoderAdapter implements Handler
    {
        private final QpackEncoder _encoder;

        public EncoderAdapter(QpackEncoder encoder)
        {
            _encoder = encoder;
        }

        @Override
        public void onSectionAcknowledgement(int streamId) throws QpackException
        {
            _encoder.sectionAcknowledgement(streamId);
        }

        @Override
        public void onStreamCancellation(int streamId) throws QpackException
        {
            _encoder.streamCancellation(streamId);
        }

        @Override
        public void onInsertCountIncrement(int increment) throws QpackException
        {
            _encoder.insertCountIncrement(increment);
        }
    }

    public EncoderInstructionParser(QpackEncoder encoder)
    {
        this(new EncoderAdapter(encoder));
    }

    public EncoderInstructionParser(Handler handler)
    {
        _handler = handler;
        _integerParser = new NBitIntegerParser();
    }

    public void parse(ByteBuffer buffer) throws QpackException
    {
        if (buffer == null || !buffer.hasRemaining())
            return;

        switch (_state)
        {
            case IDLE:
                // Get first byte without incrementing the buffers position.
                byte firstByte = buffer.get(buffer.position());
                if ((firstByte & 0x80) != 0)
                {
                    _state = State.SECTION_ACKNOWLEDGEMENT;
                    _integerParser.setPrefix(SECTION_ACKNOWLEDGEMENT_PREFIX);
                    parseSectionAcknowledgment(buffer);
                }
                else if ((firstByte & 0x40) != 0)
                {
                    _state = State.STREAM_CANCELLATION;
                    _integerParser.setPrefix(STREAM_CANCELLATION_PREFIX);
                    parseStreamCancellation(buffer);
                }
                else
                {
                    _state = State.INSERT_COUNT_INCREMENT;
                    _integerParser.setPrefix(INSERT_COUNT_INCREMENT_PREFIX);
                    parseInsertCountIncrement(buffer);
                }
                break;

            case SECTION_ACKNOWLEDGEMENT:
                parseSectionAcknowledgment(buffer);
                break;

            case STREAM_CANCELLATION:
                parseStreamCancellation(buffer);
                break;

            case INSERT_COUNT_INCREMENT:
                parseInsertCountIncrement(buffer);
                break;

            default:
                throw new IllegalStateException(_state.name());
        }
    }

    private void parseSectionAcknowledgment(ByteBuffer buffer) throws QpackException
    {
        int streamId = _integerParser.decode(buffer);
        if (streamId >= 0)
        {
            reset();
            _handler.onSectionAcknowledgement(streamId);
        }
    }

    private void parseStreamCancellation(ByteBuffer buffer) throws QpackException
    {
        int streamId = _integerParser.decode(buffer);
        if (streamId >= 0)
        {
            reset();
            _handler.onStreamCancellation(streamId);
        }
    }

    private void parseInsertCountIncrement(ByteBuffer buffer) throws QpackException
    {
        int increment = _integerParser.decode(buffer);
        if (increment >= 0)
        {
            reset();
            _handler.onInsertCountIncrement(increment);
        }
    }

    public void reset()
    {
        _state = State.IDLE;
        _integerParser.reset();
    }
}
