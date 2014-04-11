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

package org.eclipse.jetty.spdy.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Settings;
import org.eclipse.jetty.spdy.frames.SettingsFrame;

public class SettingsBodyParser extends ControlFrameBodyParser
{
    private final Settings settings = new Settings();
    private final ControlFrameParser controlFrameParser;
    private State state = State.COUNT;
    private int cursor;
    private int count;
    private int idAndFlags;
    private int value;

    public SettingsBodyParser(ControlFrameParser controlFrameParser)
    {
        this.controlFrameParser = controlFrameParser;
    }

    @Override
    public boolean parse(ByteBuffer buffer)
    {
        while (buffer.hasRemaining())
        {
            switch (state)
            {
                case COUNT:
                {
                    if (buffer.remaining() >= 4)
                    {
                        count = buffer.getInt();
                        state = State.ID_FLAGS;
                    }
                    else
                    {
                        state = State.COUNT_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case COUNT_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    count += (currByte & 0xFF) << 8 * cursor;
                    if (cursor == 0)
                        state = State.ID_FLAGS;
                    break;
                }
                case ID_FLAGS:
                {
                    if (buffer.remaining() >= 4)
                    {
                        idAndFlags = convertIdAndFlags(controlFrameParser.getVersion(), buffer.getInt());
                        state = State.VALUE;
                    }
                    else
                    {
                        state = State.ID_FLAGS_BYTES;
                        cursor = 4;
                    }
                    break;
                }
                case ID_FLAGS_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    value += (currByte & 0xFF) << 8 * cursor;
                    if (cursor == 0)
                    {
                        idAndFlags = convertIdAndFlags(controlFrameParser.getVersion(), value);
                        state = State.VALUE;
                    }
                    break;
                }
                case VALUE:
                {
                    if (buffer.remaining() >= 4)
                    {
                        value = buffer.getInt();
                        if (onPair())
                            return true;
                    }
                    else
                    {
                        state = State.VALUE_BYTES;
                        cursor = 4;
                        value = 0;
                    }
                    break;
                }
                case VALUE_BYTES:
                {
                    byte currByte = buffer.get();
                    --cursor;
                    value += (currByte & 0xFF) << 8 * cursor;
                    if (cursor == 0)
                    {
                        if (onPair())
                            return true;
                    }
                    break;
                }
                default:
                {
                    throw new IllegalStateException();
                }
            }
        }
        return false;
    }

    private int convertIdAndFlags(short version, int idAndFlags)
    {
        switch (version)
        {
            case SPDY.V2:
            {
                // A bug in the Chromium implementation forces v2 to have
                // 3 ID bytes little endian + 1 byte of flags
                // Here we normalize this to conform with v3, which is
                // 1 bytes of flag + 3 ID bytes big endian
                int result = (idAndFlags & 0x00_00_00_FF) << 24;
                result += (idAndFlags & 0x00_00_FF_00) << 8;
                result += (idAndFlags & 0x00_FF_00_00) >>> 8;
                result += (idAndFlags & 0xFF_00_00_00) >>> 24;
                return result;
            }
            case SPDY.V3:
            {
                return idAndFlags;
            }
            default:
            {
                throw new IllegalStateException();
            }
        }
    }

    private boolean onPair()
    {
        int id = idAndFlags & 0x00_FF_FF_FF;
        byte flags = (byte)((idAndFlags & 0xFF_00_00_00) >>> 24);
        settings.put(new Settings.Setting(Settings.ID.from(id), Settings.Flag.from(flags), value));
        state = State.ID_FLAGS;
        idAndFlags = 0;
        value = 0;
        --count;
        if (count == 0)
        {
            onSettings();
            return true;
        }
        return false;
    }

    private void onSettings()
    {
        SettingsFrame frame = new SettingsFrame(controlFrameParser.getVersion(), controlFrameParser.getFlags(), new Settings(settings, true));
        controlFrameParser.onControlFrame(frame);
        reset();
    }

    private void reset()
    {
        settings.clear();
        state = State.COUNT;
        cursor = 0;
        count = 0;
        idAndFlags = 0;
        value = 0;
    }

    private enum State
    {
        COUNT, COUNT_BYTES, ID_FLAGS, ID_FLAGS_BYTES, VALUE, VALUE_BYTES
    }
}
