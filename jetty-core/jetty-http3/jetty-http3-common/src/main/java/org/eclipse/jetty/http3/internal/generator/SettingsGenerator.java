//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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
import java.util.function.Consumer;

import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.VarLenInt;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.RetainableByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class SettingsGenerator extends FrameGenerator
{
    private final boolean useDirectByteBuffers;

    public SettingsGenerator(boolean useDirectByteBuffers)
    {
        this.useDirectByteBuffers = useDirectByteBuffers;
    }

    @Override
    public int generate(RetainableByteBufferPool.Accumulator accumulator, long streamId, Frame frame, Consumer<Throwable> fail)
    {
        SettingsFrame settingsFrame = (SettingsFrame)frame;
        return generateSettings(accumulator, settingsFrame);
    }

    private int generateSettings(RetainableByteBufferPool.Accumulator accumulator, SettingsFrame frame)
    {
        int length = 0;
        Map<Long, Long> settings = frame.getSettings();
        for (Map.Entry<Long, Long> e : settings.entrySet())
        {
            length += VarLenInt.length(e.getKey()) + VarLenInt.length(e.getValue());
        }
        int capacity = VarLenInt.length(frame.getFrameType().type()) + VarLenInt.length(length) + length;
        RetainableByteBuffer buffer = accumulator.acquire(capacity, useDirectByteBuffers);
        ByteBuffer byteBuffer = buffer.getByteBuffer();
        VarLenInt.encode(byteBuffer, frame.getFrameType().type());
        VarLenInt.encode(byteBuffer, length);
        for (Map.Entry<Long, Long> e : settings.entrySet())
        {
            VarLenInt.encode(byteBuffer, e.getKey());
            VarLenInt.encode(byteBuffer, e.getValue());
        }
        BufferUtil.flipToFlush(byteBuffer, 0);
        accumulator.append(buffer);
        return capacity;
    }
}
