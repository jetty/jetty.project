//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.generator;

import java.util.List;
import java.util.Map;

import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;

public class SettingsGenerator extends FrameGenerator
{
    public SettingsGenerator(HeaderGenerator headerGenerator)
    {
        super(headerGenerator);
    }

    @Override
    public int generate(List<ReadableBuffer> accumulator, Frame frame)
    {
        SettingsFrame settingsFrame = (SettingsFrame)frame;
        return generateSettings(accumulator, settingsFrame.getSettings(), settingsFrame.isReply());
    }

    public int generateSettings(List<ReadableBuffer> accumulator, Map<Integer, Integer> settings, boolean reply)
    {
        // Two bytes for the identifier, four bytes for the value.
        int entryLength = 2 + 4;
        int length = entryLength * settings.size();
        if (length > getMaxFrameSize())
            throw new IllegalArgumentException("Invalid settings, too big");

        WritableBuffer wb = generateHeader(FrameType.SETTINGS, length, reply ? Flags.ACK : Flags.NONE, 0);
        for (Map.Entry<Integer, Integer> entry : settings.entrySet())
        {
            wb.putShort(entry.getKey().shortValue());
            wb.putInt(entry.getValue());
        }
        accumulator.add(wb.toReadable());

        return Frame.HEADER_LENGTH + length;
    }
}
