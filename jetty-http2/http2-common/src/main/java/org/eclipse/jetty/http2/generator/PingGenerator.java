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

import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.PingFrame;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class PingGenerator extends FrameGenerator
{
    public PingGenerator(HeaderGenerator headerGenerator)
    {
        super(headerGenerator);
    }

    @Override
    public int generate(ByteBufferPool.Lease lease, Frame frame)
    {
        PingFrame pingFrame = (PingFrame)frame;
        return generatePing(lease, pingFrame.getPayload(), pingFrame.isReply());
    }

    public int generatePing(ByteBufferPool.Lease lease, byte[] payload, boolean reply)
    {
        if (payload.length != PingFrame.PING_LENGTH)
            throw new IllegalArgumentException("Invalid payload length: " + payload.length);

        ByteBuffer header = generateHeader(lease, FrameType.PING, PingFrame.PING_LENGTH, reply ? Flags.ACK : Flags.NONE, 0);

        header.put(payload);

        BufferUtil.flipToFlush(header, 0);
        lease.append(header, true);

        return Frame.HEADER_LENGTH + PingFrame.PING_LENGTH;
    }
}
