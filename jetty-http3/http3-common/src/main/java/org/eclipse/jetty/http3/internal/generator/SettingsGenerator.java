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

package org.eclipse.jetty.http3.internal.generator;

import java.nio.ByteBuffer;
import java.util.Map;

import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.VarLenInt;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class SettingsGenerator extends FrameGenerator
{
    @Override
    public int generate(ByteBufferPool.Lease lease, long streamId, Frame frame)
    {
        SettingsFrame settingsFrame = (SettingsFrame)frame;
        return generateSettings(lease, settingsFrame);
    }

    private int generateSettings(ByteBufferPool.Lease lease, SettingsFrame frame)
    {
        int length = 0;
        Map<Long, Long> settings = frame.getSettings();
        for (Map.Entry<Long, Long> e : settings.entrySet())
        {
            length += VarLenInt.length(e.getKey()) + VarLenInt.length(e.getValue());
        }
        int capacity = VarLenInt.length(frame.getFrameType().type()) + VarLenInt.length(length) + length;
        // TODO: configure buffer directness.
        ByteBuffer buffer = lease.acquire(capacity, true);
        VarLenInt.generate(buffer, frame.getFrameType().type());
        VarLenInt.generate(buffer, length);
        for (Map.Entry<Long, Long> e : settings.entrySet())
        {
            VarLenInt.generate(buffer, e.getKey());
            VarLenInt.generate(buffer, e.getValue());
        }
        BufferUtil.flipToFlush(buffer, 0);
        lease.append(buffer, true);
        return capacity;
    }
}
