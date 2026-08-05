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

import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;

public class PingGenerator extends FrameGenerator
{
    public PingGenerator(HeaderGenerator headerGenerator)
    {
        super(headerGenerator);
    }

    @Override
    public int generate(List<RetainableByteBuffer> accumulator, Frame frame)
    {
        PingFrame pingFrame = (PingFrame)frame;
        return generatePing(accumulator, pingFrame.getPayload(), pingFrame.isReply());
    }

    public int generatePing(List<RetainableByteBuffer> accumulator, byte[] payload, boolean reply)
    {
        if (payload.length != PingFrame.PING_LENGTH)
            throw new IllegalArgumentException("Invalid payload length: " + payload.length);

        RetainableByteBuffer.Mutable buffer = generateHeader(FrameType.PING, PingFrame.PING_LENGTH, reply ? Flags.ACK : Flags.NONE, 0);
        buffer.put(payload);
        accumulator.add(buffer);
        return Frame.HEADER_LENGTH + PingFrame.PING_LENGTH;
    }
}
