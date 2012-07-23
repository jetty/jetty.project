// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.protocol;

import static org.hamcrest.Matchers.*;

import java.util.LinkedList;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.io.OutgoingFrames;
import org.junit.Assert;

public class OutgoingFramesCapture implements OutgoingFrames
{
    public static class Write<C>
    {
        public C context;
        public Callback<C> callback;
        public WebSocketFrame frame;
    }

    private LinkedList<Write<?>> writes = new LinkedList<>();

    public void assertFrameCount(int expectedCount)
    {
        Assert.assertThat("Captured frame count",writes.size(),is(expectedCount));
    }

    public void assertHasFrame(OpCode op)
    {
        Assert.assertThat(op.name(),getFrameCount(op),greaterThanOrEqualTo(1));
    }

    public void assertHasFrame(OpCode op, int expectedCount)
    {
        Assert.assertThat(op.name(),getFrameCount(op),is(expectedCount));
    }

    public void assertHasNoFrames()
    {
        Assert.assertThat("Has no frames",writes.size(),is(0));
    }

    public int getFrameCount(OpCode op)
    {
        int count = 0;
        for (Write<?> write : writes)
        {
            WebSocketFrame frame = write.frame;
            if (frame.getOpCode() == op)
            {
                count++;
            }
        }
        return count;
    }

    public LinkedList<Write<?>> getWrites()
    {
        return writes;
    }

    @Override
    public <C> void output(C context, Callback<C> callback, WebSocketFrame frame)
    {
        Write<C> write = new Write<C>();
        write.context = context;
        write.callback = callback;
        write.frame = frame;
        writes.add(write);
    }
}
