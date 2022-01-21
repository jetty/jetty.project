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

package org.eclipse.jetty.fcgi.parser;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.http.HttpField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Parser for the PARAMS frame content.</p>
 * <pre>
 * struct small_name_small_value_params_body {
 *     ubyte nameLength;
 *     ubyte valueLength;
 *     ubyte[] nameBytes;
 *     ubyte[] valueBytes;
 * }
 *
 * struct small_name_large_value_params_body {
 *     ubyte nameLength;
 *     uint valueLength;
 *     ubyte[] nameBytes;
 *     ubyte[] valueBytes;
 * }
 *
 * struct large_name_small_value_params_body {
 *     uint nameLength;
 *     ubyte valueLength;
 *     ubyte[] nameBytes;
 *     ubyte[] valueBytes;
 * }
 *
 * struct large_name_large_value_params_body {
 *     uint nameLength;
 *     uint valueLength;
 *     ubyte[] nameBytes;
 *     ubyte[] valueBytes;
 * }
 * </pre>
 */
public class ParamsContentParser extends ContentParser
{
    private static final Logger LOG = LoggerFactory.getLogger(ParamsContentParser.class);

    private final ServerParser.Listener listener;
    private State state = State.LENGTH;
    private int cursor;
    private int length;
    private int nameLength;
    private int valueLength;
    private byte[] nameBytes;
    private byte[] valueBytes;

    public ParamsContentParser(HeaderParser headerParser, ServerParser.Listener listener)
    {
        super(headerParser);
        this.listener = listener;
    }

    @Override
    public Result parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining() || state == State.PARAM)
        {
            switch (state)
            {
                case LENGTH:
                {
                    length = getContentLength();
                    state = State.NAME_LENGTH;
                    break;
                }
                case NAME_LENGTH:
                {
                    if (isLargeLength(buffer))
                    {
                        if (buffer.remaining() >= 4)
                        {
                            nameLength = buffer.getInt() & 0x7F_FF;
                            state = State.VALUE_LENGTH;
                            length -= 4;
                        }
                        else
                        {
                            state = State.NAME_LENGTH_BYTES;
                            cursor = 0;
                        }
                    }
                    else
                    {
                        nameLength = buffer.get() & 0xFF;
                        state = State.VALUE_LENGTH;
                        --length;
                    }
                    break;
                }
                case NAME_LENGTH_BYTES:
                {
                    int quarterInt = buffer.get() & 0xFF;
                    nameLength = (nameLength << 8) + quarterInt;
                    --length;
                    if (++cursor == 4)
                    {
                        nameLength &= 0x7F_FF;
                        state = State.VALUE_LENGTH;
                    }
                    break;
                }
                case VALUE_LENGTH:
                {
                    if (isLargeLength(buffer))
                    {
                        if (buffer.remaining() >= 4)
                        {
                            valueLength = buffer.getInt() & 0x7F_FF;
                            state = State.NAME;
                            length -= 4;
                        }
                        else
                        {
                            state = State.VALUE_LENGTH_BYTES;
                            cursor = 0;
                        }
                    }
                    else
                    {
                        valueLength = buffer.get() & 0xFF;
                        state = State.NAME;
                        --length;
                    }
                    break;
                }
                case VALUE_LENGTH_BYTES:
                {
                    int quarterInt = buffer.get() & 0xFF;
                    valueLength = (valueLength << 8) + quarterInt;
                    --length;
                    if (++cursor == 4)
                    {
                        valueLength &= 0x7F_FF;
                        state = State.NAME;
                    }
                    break;
                }
                case NAME:
                {
                    nameBytes = new byte[nameLength];
                    if (buffer.remaining() >= nameLength)
                    {
                        buffer.get(nameBytes);
                        state = State.VALUE;
                        length -= nameLength;
                    }
                    else
                    {
                        state = State.NAME_BYTES;
                        cursor = 0;
                    }
                    break;
                }
                case NAME_BYTES:
                {
                    nameBytes[cursor] = buffer.get();
                    --length;
                    if (++cursor == nameLength)
                        state = State.VALUE;
                    break;
                }
                case VALUE:
                {
                    valueBytes = new byte[valueLength];
                    if (buffer.remaining() >= valueLength)
                    {
                        buffer.get(valueBytes);
                        state = State.PARAM;
                        length -= valueLength;
                    }
                    else
                    {
                        state = State.VALUE_BYTES;
                        cursor = 0;
                    }
                    break;
                }
                case VALUE_BYTES:
                {
                    valueBytes[cursor] = buffer.get();
                    --length;
                    if (++cursor == valueLength)
                        state = State.PARAM;
                    break;
                }
                case PARAM:
                {
                    Charset utf8 = StandardCharsets.UTF_8;
                    onParam(new String(nameBytes, utf8), new String(valueBytes, utf8));
                    partialReset();
                    if (length == 0)
                    {
                        reset();
                        return Result.COMPLETE;
                    }
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        return Result.PENDING;
    }

    @Override
    public boolean noContent()
    {
        return onParams();
    }

    protected void onParam(String name, String value)
    {
        try
        {
            listener.onHeader(getRequest(), new HttpField(name, value));
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Exception while invoking listener {}", listener, x);
        }
    }

    protected boolean onParams()
    {
        try
        {
            return listener.onHeaders(getRequest());
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Exception while invoking listener {}", listener, x);
            return false;
        }
    }

    private boolean isLargeLength(ByteBuffer buffer)
    {
        return (buffer.get(buffer.position()) & 0x80) == 0x80;
    }

    private void partialReset()
    {
        state = State.NAME_LENGTH;
        cursor = 0;
        nameLength = 0;
        valueLength = 0;
        nameBytes = null;
        valueBytes = null;
    }

    private void reset()
    {
        partialReset();
        state = State.LENGTH;
        length = 0;
    }

    private enum State
    {
        LENGTH, NAME_LENGTH, NAME_LENGTH_BYTES, VALUE_LENGTH, VALUE_LENGTH_BYTES, NAME, NAME_BYTES, VALUE, VALUE_BYTES, PARAM
    }
}
