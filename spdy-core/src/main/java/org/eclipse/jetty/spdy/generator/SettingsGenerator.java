/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.spdy.StreamException;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Settings;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.SettingsFrame;

public class SettingsGenerator extends ControlFrameGenerator
{
    @Override
    public ByteBuffer generate(ControlFrame frame) throws StreamException
    {
        SettingsFrame settingsFrame = (SettingsFrame)frame;

        Settings settings = settingsFrame.getSettings();
        int size = settings.size();
        int frameBodyLength = 4 + 8 * size;
        int totalLength = ControlFrame.HEADER_LENGTH + frameBodyLength;
        ByteBuffer buffer = ByteBuffer.allocate(totalLength);
        generateControlFrameHeader(settingsFrame, frameBodyLength, buffer);

        buffer.putInt(size);

        for (Settings.Setting setting : settings)
        {
            int id = setting.id().getCode();
            int flags = setting.flag().getCode();
            int idAndFlags = (id << 8) + flags;
            idAndFlags = convertIdAndFlags(frame.getVersion(), idAndFlags);
            buffer.putInt(idAndFlags);
            buffer.putInt(setting.value());
        }

        buffer.flip();
        return buffer;
    }

    private int convertIdAndFlags(short version, int idAndFlags)
    {
        switch (version)
        {
            case SPDY.V2:
            {
                // A bug in the Chromium implementation made v2 have
                // 3 bytes little endian + 1 byte of flags
                int result = idAndFlags & 0x00_FF_00_FF;
                result += (idAndFlags & 0xFF_00_00_00) >>> 16;
                result += (idAndFlags & 0x00_00_FF_00) << 16;
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
}
