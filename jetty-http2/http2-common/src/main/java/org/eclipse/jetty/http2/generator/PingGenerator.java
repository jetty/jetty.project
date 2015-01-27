//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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
    public void generate(ByteBufferPool.Lease lease, Frame frame)
    {
        PingFrame pingFrame = (PingFrame)frame;
        generatePing(lease, pingFrame.getPayload(), pingFrame.isReply());
    }

    public void generatePing(ByteBufferPool.Lease lease, byte[] payload, boolean reply)
    {
        if (payload.length != 8)
            throw new IllegalArgumentException("Invalid payload length: " + payload.length);

        ByteBuffer header = generateHeader(lease, FrameType.PING, 8, reply ? Flags.ACK : Flags.NONE, 0);

        header.put(payload);

        BufferUtil.flipToFlush(header, 0);
        lease.append(header, true);
    }
}
