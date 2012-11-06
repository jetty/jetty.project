//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common;

import static org.hamcrest.Matchers.*;

import java.util.LinkedList;
import java.util.concurrent.Future;

import javax.net.websocket.SendResult;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.common.extensions.AbstractJettyFrameHandler;
import org.eclipse.jetty.websocket.common.io.OutgoingFrames;
import org.junit.Assert;

public class OutgoingFramesCapture extends AbstractJettyFrameHandler implements OutgoingFrames
{
    private LinkedList<WebSocketFrame> frames = new LinkedList<>();

    public OutgoingFramesCapture()
    {
        super(null); // Do not forward frames anywhere else, capture is the end of the line
    }

    public void assertFrameCount(int expectedCount)
    {
        Assert.assertThat("Captured frame count",frames.size(),is(expectedCount));
    }

    public void assertHasFrame(byte op)
    {
        Assert.assertThat(OpCode.name(op),getFrameCount(op),greaterThanOrEqualTo(1));
    }

    public void assertHasFrame(byte op, int expectedCount)
    {
        Assert.assertThat(OpCode.name(op),getFrameCount(op),is(expectedCount));
    }

    public void assertHasNoFrames()
    {
        Assert.assertThat("Has no frames",frames.size(),is(0));
    }

    public void dump()
    {
        System.out.printf("Captured %d outgoing writes%n",frames.size());
        for (int i = 0; i < frames.size(); i++)
        {
            WebSocketFrame frame = frames.get(i);
            System.out.printf("[%3d] %s%n",i,frame);
            System.out.printf("      %s%n",BufferUtil.toDetailString(frame.getPayload()));
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

    public LinkedList<WebSocketFrame> getFrames()
    {
        return frames;
    }

    @Override
    public void handleJettyFrame(WebSocketFrame frame)
    {
        outgoingFrame(frame);
    }

    @Override
    public Future<SendResult> outgoingFrame(WebSocketFrame frame)
    {
        frames.add(frame);

        return null; // FIXME: should return completed future.
    }
}
