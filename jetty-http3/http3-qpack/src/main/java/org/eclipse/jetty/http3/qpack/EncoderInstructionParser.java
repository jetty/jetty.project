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

package org.eclipse.jetty.http3.qpack;

import java.nio.ByteBuffer;

/**
 * Receives instructions coming from the remote Decoder as a sequence of unframed instructions.
 */
public class EncoderInstructionParser
{
    private final Handler _handler;
    private final NBitIntegerParser _integerParser;
    private State _state = State.PARSING;

    private enum State
    {
        PARSING,
        SET_CAPACITY,
        REFERENCED_NAME,
        LITERAL_NAME,
        DUPLICATE
    }

    public interface Handler
    {
        void onSetDynamicTableCapacity(int capacity);

        void onDuplicate(int index);
    }

    public EncoderInstructionParser(Handler handler)
    {
        _handler = handler;
        _integerParser = new NBitIntegerParser();
    }

    public void parse(ByteBuffer buffer)
    {
        switch (_state)
        {
            case PARSING:
                byte firstByte = buffer.slice().get();
                if ((firstByte & 0x80) != 0)
                {
                    _state = State.REFERENCED_NAME;
                    parseInsertNameWithReference(buffer);
                }
                else if ((firstByte & 0x40) != 0)
                {
                    _state = State.LITERAL_NAME;
                    parseInsertWithLiteralName(buffer);
                }
                else if ((firstByte & 0x20) != 0)
                {
                    _state = State.SET_CAPACITY;
                    parseSetDynamicTableCapacity(buffer);
                }
                else
                {
                    _state = State.DUPLICATE;
                    parseDuplicate(buffer);
                }
                break;

            case SET_CAPACITY:
                parseSetDynamicTableCapacity(buffer);
                break;

            case DUPLICATE:
                parseDuplicate(buffer);
                break;

            case LITERAL_NAME:
                parseInsertWithLiteralName(buffer);
                break;

            case REFERENCED_NAME:
                parseInsertNameWithReference(buffer);
                break;

            default:
                throw new IllegalStateException(_state.name());
        }
    }

    private void parseInsertNameWithReference(ByteBuffer buffer)
    {
        // TODO
        // Single bit boolean whether reference is to dynamic or static table.
        // Index with 6-bit prefix.
        // Single bit wither it is huffman encoded.
        // Length of the encoded string.
        // The string itself.
    }

    private void parseInsertWithLiteralName(ByteBuffer buffer)
    {
        // TODO
        // Single bit whether name is huffman encoded.
        // Length of name with 5-bit prefix.
        // Name bytes.
        // Single bit whether value is huffman encoded.
        // Value length with 7-bit prefix.
        // Value bytes.
    }

    private void parseDuplicate(ByteBuffer buffer)
    {
        int index = _integerParser.decode(buffer, 5);
        if (index >= 0)
        {
            _state = State.PARSING;
            _handler.onDuplicate(index);
        }
    }

    private void parseSetDynamicTableCapacity(ByteBuffer buffer)
    {
        int capacity = _integerParser.decode(buffer, 5);
        if (capacity >= 0)
        {
            _state = State.PARSING;
            _handler.onSetDynamicTableCapacity(capacity);
        }
    }
}
