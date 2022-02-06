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

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

import org.eclipse.jetty.util.Callback;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class IncomingFramesCapture implements IncomingFrames
{
    public BlockingQueue<Frame> frames = new LinkedBlockingDeque<>();

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        Frame copy = Frame.copy(frame);
        frames.offer(copy);
        callback.succeeded();
    }

    public void assertHasOpCount(byte opCode, int expectedCount)
    {
        assertThat("Frame Count [op=" + opCode + "]", getFrameCount(opCode), is(expectedCount));
    }

    public void assertFrameCount(int expectedCount)
    {
        assertThat("Frame Count", frames.size(), is(expectedCount));
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
}
