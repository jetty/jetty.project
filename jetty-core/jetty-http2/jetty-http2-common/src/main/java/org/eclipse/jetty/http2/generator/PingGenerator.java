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

import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.io.RetainableByteBuffer;

public class PingGenerator extends FrameGenerator
{
    public PingGenerator(HeaderGenerator headerGenerator)
    {
        super(headerGenerator);
    }

    @Override
    public int generate(RetainableByteBuffer.Mutable accumulator, Frame frame)
    {
        PingFrame pingFrame = (PingFrame)frame;
        return generatePing(accumulator, pingFrame.getPayload(), pingFrame.isReply());
    }

    public int generatePing(RetainableByteBuffer.Mutable accumulator, byte[] payload, boolean reply)
    {
        if (payload.length != PingFrame.PING_LENGTH)
            throw new IllegalArgumentException("Invalid payload length: " + payload.length);

        generateHeader(accumulator, FrameType.PING, PingFrame.PING_LENGTH, reply ? Flags.ACK : Flags.NONE, 0);
        accumulator.put(payload, 0, payload.length);
        return Frame.HEADER_LENGTH + PingFrame.PING_LENGTH;
    }
}
