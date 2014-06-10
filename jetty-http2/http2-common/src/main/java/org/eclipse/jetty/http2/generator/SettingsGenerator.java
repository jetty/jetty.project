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

package org.eclipse.jetty.http2.generator;

import java.nio.ByteBuffer;
import java.util.Map;

import org.eclipse.jetty.http2.frames.Flag;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.SettingsFrame;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

public class SettingsGenerator extends FrameGenerator
{
    public SettingsGenerator(HeaderGenerator headerGenerator)
    {
        super(headerGenerator);
    }

    @Override
    public void generate(ByteBufferPool.Lease lease, Frame frame, Callback callback)
    {
        SettingsFrame settingsFrame = (SettingsFrame)frame;
        generateSettings(lease, settingsFrame.getSettings(), settingsFrame.isReply());
    }

    public void generateSettings(ByteBufferPool.Lease lease, Map<Integer, Integer> settings, boolean reply)
    {
        ByteBuffer header = generateHeader(lease, FrameType.SETTINGS, 5 * settings.size(), reply ? Flag.ACK : Flag.NONE, 0);

        for (Map.Entry<Integer, Integer> entry : settings.entrySet())
        {
            header.put(entry.getKey().byteValue());
            header.putInt(entry.getValue());
        }

        BufferUtil.flipToFlush(header, 0);
        lease.append(header, true);
    }
}
