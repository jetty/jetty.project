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

package org.eclipse.jetty.websocket.common.test;

import java.util.concurrent.LinkedBlockingDeque;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.BatchMode;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.OutgoingFrames;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

public class OutgoingFramesCapture implements OutgoingFrames
{
    private LinkedBlockingDeque<WebSocketFrame> frames = new LinkedBlockingDeque<>();

    public void assertFrameCount(int expectedCount)
    {
        assertThat("Captured frame count", frames.size(), is(expectedCount));
    }

    public void assertHasFrame(byte op)
    {
        assertThat(OpCode.name(op), getFrameCount(op), greaterThanOrEqualTo(1));
    }

    public void assertHasFrame(byte op, int expectedCount)
    {
        assertThat(OpCode.name(op), getFrameCount(op), is(expectedCount));
    }

    public void assertHasNoFrames()
    {
        assertThat("Has no frames", frames.size(), is(0));
    }

    public void dump()
    {
        System.out.printf("Captured %d outgoing writes%n", frames.size());
        int i = 0;
        for (WebSocketFrame frame : frames)
        {
            System.out.printf("[%3d] %s%n", i, frame);
            System.out.printf("      %s%n", BufferUtil.toDetailString(frame.getPayload()));
            i++;
        }
    }

    public int getFrameCount(byte op)
    {
        int count = 0;
        for (WebSocketFrame frame : frames)
        {
            if (frame.getOpCode() == op)
            {
                count++;
            }
        }
        return count;
    }

    public LinkedBlockingDeque<WebSocketFrame> getFrames()
    {
        return frames;
    }

    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback, BatchMode batchMode)
    {
        frames.add(WebSocketFrame.copy(frame));
        if (callback != null)
        {
            callback.writeSuccess();
        }
    }
}
