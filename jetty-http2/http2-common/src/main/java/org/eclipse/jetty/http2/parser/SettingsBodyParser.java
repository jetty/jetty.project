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

import org.eclipse.jetty.http2.ErrorCodes;
import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class SettingsBodyParser extends BodyParser
{
    private static final Logger LOG = Log.getLogger(SettingsBodyParser.class);
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
    protected boolean emptyBody(ByteBuffer buffer)
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
                    // SPEC: wrong streamId is treated as connection error.
                    if (getStreamId() != 0)
                    {
                        BufferUtil.clear(buffer);
                        return notifyConnectionFailure(ErrorCodes.PROTOCOL_ERROR, "invalid_settings_frame");
                    }
                    length = getBodyLength();
                    settings = new HashMap<>();
                    state = State.SETTING_ID;
                    break;
                }
                case SETTING_ID:
                {
                    if (buffer.remaining() >= 2)
                    {
                        settingId = buffer.getShort() & 0xFF_FF;
                        state = State.SETTING_VALUE;
                        length -= 2;
                        if (length <= 0)
                        {
                            BufferUtil.clear(buffer);
                            return notifyConnectionFailure(ErrorCodes.FRAME_SIZE_ERROR, "invalid_settings_frame");
                        }
                    }
                    else
                    {
                        cursor = 2;
                        settingId = 0;
                        state = State.SETTING_ID_BYTES;
                    }
                    break;
                }
                case SETTING_ID_BYTES:
                {
                    int currByte = buffer.get() & 0xFF;
                    --cursor;
                    settingId += currByte << (8 * cursor);
                    --length;
                    if (length <= 0)
                    {
                        BufferUtil.clear(buffer);
                        return notifyConnectionFailure(ErrorCodes.FRAME_SIZE_ERROR, "invalid_settings_frame");
                    }
                    if (cursor == 0)
                    {
                        state = State.SETTING_VALUE;
                    }
                    break;
                }
                case SETTING_VALUE:
                {
                    if (buffer.remaining() >= 4)
                    {
                        settingValue = buffer.getInt();
                        if (LOG.isDebugEnabled())
                            LOG.debug(String.format("setting %d=%d",settingId, settingValue));
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
                        BufferUtil.clear(buffer);
                        return notifyConnectionFailure(ErrorCodes.FRAME_SIZE_ERROR, "invalid_settings_frame");
                    }
                    if (cursor == 0)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug(String.format("setting %d=%d",settingId, settingValue));
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
        SettingsFrame frame = new SettingsFrame(settings, hasFlag(Flags.ACK));
        reset();
        return notifySettings(frame) ? Result.ASYNC : Result.COMPLETE;
    }

    private enum State
    {
        PREPARE, SETTING_ID, SETTING_ID_BYTES, SETTING_VALUE, SETTING_VALUE_BYTES
    }
}
