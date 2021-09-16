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

package org.eclipse.jetty.http3.internal.parser;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.jetty.http3.ErrorCode;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.VarLenInt;

public class SettingsBodyParser extends BodyParser
{
    private final VarLenInt varLenInt = new VarLenInt();
    private State state = State.INIT;
    private long length;
    private long key;
    private Map<Long, Long> settings;

    public SettingsBodyParser(HeaderParser headerParser)
    {
        super(1, headerParser);
    }

    private void reset()
    {
        varLenInt.reset();
        state = State.INIT;
        length = 0;
        key = 0;
        settings = null;
    }

    @Override
    protected Frame emptyBody(ByteBuffer buffer)
    {
        return onSettings(Map.of());
    }

    @Override
    public Frame parse(ByteBuffer buffer) throws ParseException
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case INIT:
                {
                    length = getBodyLength();
                    settings = new LinkedHashMap<>();
                    state = State.KEY;
                    break;
                }
                case KEY:
                {
                    if (varLenInt.parseLong(buffer, v ->
                    {
                        key = v;
                        length -= VarLenInt.length(v);
                    }))
                    {
                        if (settings.containsKey(key))
                            throw new ParseException(ErrorCode.SETTINGS_ERROR.code(), "settings_duplicate");
                        if (SettingsFrame.isReserved(key))
                            throw new ParseException(ErrorCode.SETTINGS_ERROR.code(), "settings_reserved");
                        if (length > 0)
                            state = State.VALUE;
                        else
                            throw new ParseException(ErrorCode.FRAME_ERROR.code(), "settings_invalid_format");
                        break;
                    }
                    return null;
                }
                case VALUE:
                {
                    if (varLenInt.parseLong(buffer, v ->
                    {
                        settings.put(key, v);
                        length -= VarLenInt.length(v);
                    }))
                    {
                        if (length > 0)
                        {
                            // TODO: count keys, if too many -> error.
                            state = State.KEY;
                        }
                        else if (length == 0)
                        {
                            Map<Long, Long> settings = this.settings;
                            reset();
                            return onSettings(settings);
                        }
                        else
                        {
                            throw new ParseException(ErrorCode.FRAME_ERROR.code(), "settings_invalid_format");
                        }
                        break;
                    }
                    return null;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        return null;
    }

    private SettingsFrame onSettings(Map<Long, Long> settings)
    {
        return new SettingsFrame(settings);
    }

    private enum State
    {
        INIT, KEY, VALUE
    }
}
