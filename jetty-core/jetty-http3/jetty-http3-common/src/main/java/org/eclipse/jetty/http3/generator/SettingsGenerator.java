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

package org.eclipse.jetty.http3.generator;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class SettingsGenerator extends FrameGenerator
{
    private final boolean useDirectByteBuffers;

    public SettingsGenerator(WritableBufferPool bufferPool, boolean useDirectByteBuffers)
    {
        super(bufferPool);
        this.useDirectByteBuffers = useDirectByteBuffers;
    }

    public long generate(List<RetainableByteBuffer> accumulator, long streamId, Frame frame, Consumer<Throwable> fail)
    {
        SettingsFrame settingsFrame = (SettingsFrame)frame;
        return generateSettings(accumulator, settingsFrame);
    }

    private long generateSettings(List<RetainableByteBuffer> accumulator, SettingsFrame frame)
    {
        int length = 0;
        Map<Long, Long> settings = frame.getSettings();
        for (Map.Entry<Long, Long> e : settings.entrySet())
        {
            length += VarLenInt.length(e.getKey()) + VarLenInt.length(e.getValue());
        }
        int capacity = VarLenInt.length(frame.getFrameType().type()) + VarLenInt.length(length) + length;
        RetainableByteBuffer.Mutable buffer = getByteBufferPool().acquire(capacity, useDirectByteBuffers);
        VarLenInt.encode(buffer, frame.getFrameType().type());
        VarLenInt.encode(buffer, length);
        for (Map.Entry<Long, Long> e : settings.entrySet())
        {
            VarLenInt.encode(buffer, e.getKey());
            VarLenInt.encode(buffer, e.getValue());
        }
        accumulator.add(buffer);
        return capacity;
    }
}
