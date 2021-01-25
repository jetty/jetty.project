//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http2.generator;

import java.nio.ByteBuffer;
import java.util.Map;

import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class SettingsGenerator extends FrameGenerator
{
    public SettingsGenerator(HeaderGenerator headerGenerator)
    {
        super(headerGenerator);
    }

    @Override
    public int generate(ByteBufferPool.Lease lease, Frame frame)
    {
        SettingsFrame settingsFrame = (SettingsFrame)frame;
        return generateSettings(lease, settingsFrame.getSettings(), settingsFrame.isReply());
    }

    public int generateSettings(ByteBufferPool.Lease lease, Map<Integer, Integer> settings, boolean reply)
    {
        // Two bytes for the identifier, four bytes for the value.
        int entryLength = 2 + 4;
        int length = entryLength * settings.size();
        if (length > getMaxFrameSize())
            throw new IllegalArgumentException("Invalid settings, too big");

        ByteBuffer header = generateHeader(lease, FrameType.SETTINGS, length, reply ? Flags.ACK : Flags.NONE, 0);

        for (Map.Entry<Integer, Integer> entry : settings.entrySet())
        {
            header.putShort(entry.getKey().shortValue());
            header.putInt(entry.getValue());
        }

        BufferUtil.flipToFlush(header, 0);
        lease.append(header, true);

        return Frame.HEADER_LENGTH + length;
    }
}
