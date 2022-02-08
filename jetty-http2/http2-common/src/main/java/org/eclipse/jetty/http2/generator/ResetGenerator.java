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

package org.eclipse.jetty.http2.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.ResetFrame;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class ResetGenerator extends FrameGenerator
{
    public ResetGenerator(HeaderGenerator headerGenerator)
    {
        super(headerGenerator);
    }

    @Override
    public int generate(ByteBufferPool.Lease lease, Frame frame)
    {
        ResetFrame resetFrame = (ResetFrame)frame;
        return generateReset(lease, resetFrame.getStreamId(), resetFrame.getError());
    }

    public int generateReset(ByteBufferPool.Lease lease, int streamId, int error)
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);

        ByteBuffer header = generateHeader(lease, FrameType.RST_STREAM, ResetFrame.RESET_LENGTH, Flags.NONE, streamId);
        header.putInt(error);
        BufferUtil.flipToFlush(header, 0);
        lease.append(header, true);

        return Frame.HEADER_LENGTH + ResetFrame.RESET_LENGTH;
    }
}
