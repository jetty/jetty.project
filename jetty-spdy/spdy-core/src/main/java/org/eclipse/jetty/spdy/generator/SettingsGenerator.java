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

package org.eclipse.jetty.spdy.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.spdy.api.SPDY;
import org.eclipse.jetty.spdy.api.Settings;
import org.eclipse.jetty.spdy.frames.ControlFrame;
import org.eclipse.jetty.spdy.frames.SettingsFrame;
import org.eclipse.jetty.util.BufferUtil;

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
        ByteBuffer buffer = getByteBufferPool().acquire(totalLength, Generator.useDirectBuffers);
        BufferUtil.clearToFill(buffer);
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
                int idAndFlags = (id << 8) + (flags & 0xFF);
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
