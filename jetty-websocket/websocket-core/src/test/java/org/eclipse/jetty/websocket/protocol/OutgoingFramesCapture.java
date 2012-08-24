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

package org.eclipse.jetty.websocket.protocol;

import java.util.LinkedList;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.io.OutgoingFrames;
import org.junit.Assert;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

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
        Assert.assertThat("Has no frames",writes.size(),is(0));
    }

    public void dump()
    {
        System.out.printf("Captured %d outgoing writes%n",writes.size());
        for (int i = 0; i < writes.size(); i++)
        {
            Write<?> write = writes.get(i);
            System.out.printf("[%3d] %s | %s | %s%n",i,write.context,write.callback,write.frame);
            System.out.printf("          %s%n",BufferUtil.toDetailString(write.frame.getPayload()));
        }
    }

    public int getFrameCount(byte op)
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
