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

package org.eclipse.jetty.websocket.core;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class OutgoingFramesCapture implements OutgoingFrames
{
    public BlockingQueue<Frame> frames = new LinkedBlockingDeque<>();

    public void assertFrameCount(int expectedCount)
    {
        assertThat("Frame Count", frames.size(), is(expectedCount));
    }

    public void assertHasOpCount(byte opCode, int expectedCount)
    {
        assertThat("Frame Count [op=" + opCode + "]", getFrameCount(opCode), is(expectedCount));
    }

    public void dump()
    {
        System.out.printf("Captured %d outgoing writes%n", frames.size());
        int i = 0;
        for (Frame frame : frames)
        {
            System.out.printf("[%3d] %s%n", i++, frame);
            System.out.printf("      %s%n", BufferUtil.toDetailString(frame.getPayload()));
        }
    }

    public int getFrameCount(byte op)
    {
        int count = 0;
        for (Frame frame : frames)
        {
            if (frame.getOpCode() == op)
            {
                count++;
            }
        }
        return count;
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, boolean batch)
    {
        frames.add(Frame.copy(frame));
        // Consume bytes
        ByteBuffer payload = frame.getPayload();
        payload.position(payload.limit());
        // notify callback
        if (callback != null)
        {
            callback.succeeded();
        }
    }
}
