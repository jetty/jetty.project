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
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;

public class PriorityGenerator extends FrameGenerator
{
    public PriorityGenerator(HeaderGenerator headerGenerator)
    {
        super(headerGenerator);
    }

    @Override
    public int generate(ByteBufferPool.Accumulator accumulator, Frame frame)
    {
        PriorityFrame priorityFrame = (PriorityFrame)frame;
        return generatePriority(accumulator, priorityFrame.getStreamId(), priorityFrame.getParentStreamId(), priorityFrame.getWeight(), priorityFrame.isExclusive());
    }

    public int generatePriority(ByteBufferPool.Accumulator accumulator, int streamId, int parentStreamId, int weight, boolean exclusive)
    {
        RetainableByteBuffer header = generateHeader(FrameType.PRIORITY, PriorityFrame.PRIORITY_LENGTH, Flags.NONE, streamId);
        ByteBuffer byteBuffer = header.getByteBuffer();
        generatePriorityBody(byteBuffer, streamId, parentStreamId, weight, exclusive);
        BufferUtil.flipToFlush(byteBuffer, 0);
        accumulator.append(header);
        return Frame.HEADER_LENGTH + PriorityFrame.PRIORITY_LENGTH;
    }

    public void generatePriorityBody(ByteBuffer header, int streamId, int parentStreamId, int weight, boolean exclusive)
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);
        if (parentStreamId < 0)
            throw new IllegalArgumentException("Invalid parent stream id: " + parentStreamId);
        if (parentStreamId == streamId)
            throw new IllegalArgumentException("Stream " + streamId + " cannot depend on stream " + parentStreamId);
        if (weight < 1 || weight > 256)
            throw new IllegalArgumentException("Invalid weight: " + weight);

        if (exclusive)
            parentStreamId |= 0x80_00_00_00;
        header.putInt(parentStreamId);
        // SPEC: for RFC 7540 weight is 1..256, for RFC 9113 is an unused value.
        header.put((byte)(weight - 1));
    }
}
