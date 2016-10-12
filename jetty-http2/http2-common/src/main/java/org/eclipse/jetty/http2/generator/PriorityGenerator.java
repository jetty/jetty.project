//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class PriorityGenerator extends FrameGenerator
{
    public PriorityGenerator(HeaderGenerator headerGenerator)
    {
        super(headerGenerator);
    }

    @Override
    public int generate(ByteBufferPool.Lease lease, Frame frame)
    {
        PriorityFrame priorityFrame = (PriorityFrame)frame;
        return generatePriority(lease, priorityFrame.getStreamId(), priorityFrame.getParentStreamId(), priorityFrame.getWeight(), priorityFrame.isExclusive());
    }

    public int generatePriority(ByteBufferPool.Lease lease, int streamId, int parentStreamId, int weight, boolean exclusive)
    {
        ByteBuffer header = generateHeader(lease, FrameType.PRIORITY, PriorityFrame.PRIORITY_LENGTH, Flags.NONE, streamId);
        generatePriorityBody(header, streamId, parentStreamId, weight, exclusive);
        BufferUtil.flipToFlush(header, 0);
        lease.append(header, true);
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
        header.put((byte)(weight - 1));
    }
}
