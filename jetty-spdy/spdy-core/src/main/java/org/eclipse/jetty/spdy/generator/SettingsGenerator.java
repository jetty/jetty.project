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

import org.eclipse.jetty.spdy.ByteBufferPool;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Settings;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.SettingsFrame;

public class SettingsGenerator extends ControlFrameGenerator
{
    public SettingsGenerator(ByteBufferPool bufferPool)
    {
        super(bufferPool);
    }

    @Override
    public ByteBuffer generate(ControlFrame frame)
    {
        SettingsFrame settingsFrame = (SettingsFrame)frame;

        Settings settings = settingsFrame.getSettings();
        int size = settings.size();
        int frameBodyLength = 4 + 8 * size;
        int totalLength = ControlFrame.HEADER_LENGTH + frameBodyLength;
        ByteBuffer buffer = getByteBufferPool().acquire(totalLength, true);
        generateControlFrameHeader(settingsFrame, frameBodyLength, buffer);

        buffer.putInt(size);

        for (Settings.Setting setting : settings)
        {
            int id = setting.id().code();
            byte flags = setting.flag().code();
            int idAndFlags = convertIdAndFlags(frame.getVersion(), id, flags);
            buffer.putInt(idAndFlags);
            buffer.putInt(setting.value());
        }

        buffer.flip();
        return buffer;
    }

    private int convertIdAndFlags(short version, int id, byte flags)
    {
        switch (version)
        {
            case SPDY.V2:
            {
                // In v2 the format is 24 bits of ID + 8 bits of flag
                int idAndFlags = (id << 8) + flags;
                // A bug in the Chromium implementation forces v2 to have
                // the 3 ID bytes little endian, so we swap first and third
                int result = idAndFlags & 0x00_FF_00_FF;
                result += (idAndFlags & 0xFF_00_00_00) >>> 16;
                result += (idAndFlags & 0x00_00_FF_00) << 16;
                return result;
            }
            case SPDY.V3:
            {
                // In v3 the format is 8 bits of flags + 24 bits of ID
                return (flags << 24) + (id & 0xFF_FF_FF);
            }
            default:
            {
                throw new IllegalStateException();
            }
        }
    }
}
