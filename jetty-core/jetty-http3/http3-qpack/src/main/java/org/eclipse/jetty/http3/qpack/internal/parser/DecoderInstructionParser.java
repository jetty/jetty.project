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

package org.eclipse.jetty.http3.qpack.internal.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http3.qpack.QpackException;
import org.eclipse.jetty.http3.qpack.internal.util.EncodingException;
import org.eclipse.jetty.http3.qpack.internal.util.NBitIntegerParser;
import org.eclipse.jetty.http3.qpack.internal.util.NBitStringParser;

/**
 * Parses a stream of unframed instructions for the Decoder. These instructions are sent from the remote Encoder.
 */
public class DecoderInstructionParser
{
    private final Handler _handler;
    private final NBitStringParser _stringParser;
    private final NBitIntegerParser _integerParser;
    private State _state = State.PARSING;
    private Operation _operation = Operation.NONE;

    private boolean _referenceDynamicTable;
    private int _index;
    private String _name;

    private enum State
    {
        PARSING,
        SET_CAPACITY,
        REFERENCED_NAME,
        LITERAL_NAME,
        DUPLICATE
    }

    private enum Operation
    {
        NONE,
        INDEX,
        NAME,
        VALUE,
    }

    public interface Handler
    {
        void onSetDynamicTableCapacity(int capacity) throws QpackException;

        void onDuplicate(int index) throws QpackException;

        void onInsertNameWithReference(int nameIndex, boolean isDynamicTableIndex, String value) throws QpackException;

        void onInsertWithLiteralName(String name, String value) throws QpackException;
    }

    public DecoderInstructionParser(Handler handler)
    {
        _handler = handler;
        _stringParser = new NBitStringParser();
        _integerParser = new NBitIntegerParser();
    }

    public void parse(ByteBuffer buffer) throws QpackException, EncodingException
    {
        if (buffer == null || !buffer.hasRemaining())
            return;

        switch (_state)
        {
            case PARSING:
                byte firstByte = buffer.get(buffer.position());
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
                    _integerParser.setPrefix(5);
                    parseSetDynamicTableCapacity(buffer);
                }
                else
                {
                    _state = State.DUPLICATE;
                    _integerParser.setPrefix(5);
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

    private void parseInsertNameWithReference(ByteBuffer buffer) throws QpackException, EncodingException
    {
        while (true)
        {
            switch (_operation)
            {
                case NONE:
                    byte firstByte = buffer.get(buffer.position());
                    _referenceDynamicTable = (firstByte & 0x40) == 0;
                    _operation = Operation.INDEX;
                    _integerParser.setPrefix(6);
                    continue;

                case INDEX:
                    _index = _integerParser.decodeInt(buffer);
                    if (_index < 0)
                        return;

                    _operation = Operation.VALUE;
                    _stringParser.setPrefix(8);
                    continue;

                case VALUE:
                    String value = _stringParser.decode(buffer);
                    if (value == null)
                        return;

                    int index = _index;
                    boolean dynamic = _referenceDynamicTable;
                    reset();
                    _handler.onInsertNameWithReference(index, dynamic, value);
                    return;

                default:
                    throw new IllegalStateException(_operation.name());
            }
        }
    }

    private void parseInsertWithLiteralName(ByteBuffer buffer) throws QpackException, EncodingException
    {
        while (true)
        {
            switch (_operation)
            {
                case NONE:
                    _operation = Operation.NAME;
                    _stringParser.setPrefix(6);
                    continue;

                case NAME:
                    _name = _stringParser.decode(buffer);
                    if (_name == null)
                        return;

                    _operation = Operation.VALUE;
                    _stringParser.setPrefix(8);
                    continue;

                case VALUE:
                    String value = _stringParser.decode(buffer);
                    if (value == null)
                        return;

                    String name = _name;
                    reset();
                    _handler.onInsertWithLiteralName(name, value);
                    return;

                default:
                    throw new IllegalStateException(_operation.name());
            }
        }
    }

    private void parseDuplicate(ByteBuffer buffer) throws QpackException
    {
        int index = _integerParser.decodeInt(buffer);
        if (index >= 0)
        {
            reset();
            _handler.onDuplicate(index);
        }
    }

    private void parseSetDynamicTableCapacity(ByteBuffer buffer) throws QpackException
    {
        int capacity = _integerParser.decodeInt(buffer);
        if (capacity >= 0)
        {
            reset();
            _handler.onSetDynamicTableCapacity(capacity);
        }
    }

    public void reset()
    {
        _stringParser.reset();
        _integerParser.reset();
        _state = State.PARSING;
        _operation = Operation.NONE;
        _referenceDynamicTable = false;
        _index = -1;
        _name = null;
    }
}
