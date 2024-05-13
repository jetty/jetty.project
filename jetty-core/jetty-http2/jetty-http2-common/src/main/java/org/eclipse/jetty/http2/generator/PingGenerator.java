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

import java.nio.ByteBuffer;

import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;

public class PingGenerator extends FrameGenerator
{
    public PingGenerator(HeaderGenerator headerGenerator)
    {
        super(headerGenerator);
    }

    @Override
    public int generate(ByteBufferPool.Accumulator accumulator, Frame frame)
    {
        PingFrame pingFrame = (PingFrame)frame;
        return generatePing(accumulator, pingFrame.getPayload(), pingFrame.isReply());
    }

    public int generatePing(ByteBufferPool.Accumulator accumulator, byte[] payload, boolean reply)
    {
        if (payload.length != PingFrame.PING_LENGTH)
            throw new IllegalArgumentException("Invalid payload length: " + payload.length);

        RetainableByteBuffer header = generateHeader(FrameType.PING, PingFrame.PING_LENGTH, reply ? Flags.ACK : Flags.NONE, 0);
        ByteBuffer byteBuffer = header.getByteBuffer();

        byteBuffer.put(payload);

        BufferUtil.flipToFlush(byteBuffer, 0);
        accumulator.append(header);

        return Frame.HEADER_LENGTH + PingFrame.PING_LENGTH;
    }
}
