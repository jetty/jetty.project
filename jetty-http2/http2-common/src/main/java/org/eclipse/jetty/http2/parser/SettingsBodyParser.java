//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2.parser;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jetty.http2.frames.Flag;
import org.eclipse.jetty.http2.frames.SettingsFrame;

public class SettingsBodyParser extends BodyParser
{
    private State state = State.PREPARE;
    private int cursor;
    private int length;
    private int settingId;
    private int settingValue;
    private Map<Integer, Integer> settings;

    public SettingsBodyParser(HeaderParser headerParser, Parser.Listener listener)
    {
        super(headerParser, listener);
    }

    private void reset()
    {
        state = State.PREPARE;
        cursor = 0;
        length = 0;
        settingId = 0;
        settingValue = 0;
        settings = null;
    }

    @Override
    protected boolean emptyBody()
    {
        return onSettings(new HashMap<Integer, Integer>()) == Result.ASYNC;
    }

    @Override
    public Result parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case PREPARE:
                {
                    length = getBodyLength();
                    settings = new HashMap<>();
                    state = State.SETTING_ID;
                    break;
                }
                case SETTING_ID:
                {
                    settingId = buffer.get() & 0xFF;
                    state = State.SETTING_VALUE;
                    --length;
                    if (length <= 0)
                    {
                        return notifyConnectionFailure(ErrorCode.PROTOCOL_ERROR, "invalid_settings_frame");
                    }
                    break;
                }
                case SETTING_VALUE:
                {
                    if (buffer.remaining() >= 4)
                    {
                        settingValue = buffer.getInt();
                        settings.put(settingId, settingValue);
                        state = State.SETTING_ID;
                        length -= 4;
                        if (length == 0)
                        {
                            return onSettings(settings);
                        }
                    }
                    else
                    {
                        cursor = 4;
                        settingValue = 0;
                        state = State.SETTING_VALUE_BYTES;
                    }
                    break;
                }
                case SETTING_VALUE_BYTES:
                {
                    int currByte = buffer.get() & 0xFF;
                    --cursor;
                    settingValue += currByte << (8 * cursor);
                    --length;
                    if (cursor > 0 && length <= 0)
                    {
                        return notifyConnectionFailure(ErrorCode.PROTOCOL_ERROR, "invalid_settings_frame");
                    }
                    if (cursor == 0)
                    {
                        settings.put(settingId, settingValue);
                        state = State.SETTING_ID;
                        if (length == 0)
                        {
                            return onSettings(settings);
                        }
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

    private Result onSettings(Map<Integer, Integer> settings)
    {
        SettingsFrame frame = new SettingsFrame(settings, hasFlag(Flag.ACK));
        reset();
        return notifySettings(frame) ? Result.ASYNC : Result.COMPLETE;
    }

    private enum State
    {
        PREPARE, SETTING_ID, SETTING_VALUE, SETTING_VALUE_BYTES
    }
}
