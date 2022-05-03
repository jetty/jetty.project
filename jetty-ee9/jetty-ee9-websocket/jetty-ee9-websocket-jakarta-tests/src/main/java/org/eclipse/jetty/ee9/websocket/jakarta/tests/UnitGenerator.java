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

package org.eclipse.jetty.websocket.jakarta.tests;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.internal.Generator;

/**
 * Extension of the default WebSocket Generator for unit testing purposes
 */
public class UnitGenerator extends Generator
{
    // Client side framing mask
    private static final byte[] MASK = {0x11, 0x22, 0x33, 0x44};
    private final boolean applyMask;

    public UnitGenerator(Behavior behavior)
    {
        applyMask = (behavior == Behavior.CLIENT);
    }

    public ByteBuffer asBuffer(List<Frame> frames)
    {
        int bufferLength = 0;
        for (Frame f : frames)
        {
            bufferLength += f.getPayloadLength() + Generator.MAX_HEADER_LENGTH;
        }

        ByteBuffer buffer = ByteBuffer.allocate(bufferLength);
        generate(buffer, frames);
        BufferUtil.flipToFlush(buffer, 0);
        return buffer;
    }

    public ByteBuffer asBuffer(Frame... frames)
    {
        return asBuffer(Arrays.asList(frames));
    }

    public void generate(ByteBuffer buffer, List<Frame> frames)
    {
        // Generate frames
        for (Frame f : frames)
        {
            if (applyMask)
                f.setMask(MASK);

            generateWholeFrame(f, buffer);
        }
    }

    public void generate(ByteBuffer buffer, Frame... frames)
    {
        // Generate frames
        for (Frame f : frames)
        {
            if (applyMask)
                f.setMask(MASK);

            generateWholeFrame(f, buffer);
        }
    }

    public ByteBuffer generate(Frame frame)
    {
        return asBuffer(Collections.singletonList(frame));
    }

    public void generate(ByteBuffer buffer, Frame frame)
    {
        if (applyMask)
            frame.setMask(MASK);
        generateWholeFrame(frame, buffer);
    }
}
