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

package org.eclipse.jetty.websocket.common.test;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;

import java.util.Queue;

import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.IncomingFrames;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.junit.Assert;

public class IncomingFramesCapture implements IncomingFrames
{
    private static final Logger LOG = Log.getLogger(IncomingFramesCapture.class);
    private EventQueue<WebSocketFrame> frames = new EventQueue<>();
    private EventQueue<Throwable> errors = new EventQueue<>();

    public void assertErrorCount(int expectedCount)
    {
        Assert.assertThat("Captured error count",errors.size(),is(expectedCount));
    }

    public void assertFrameCount(int expectedCount)
    {
        if (frames.size() != expectedCount)
        {
            // dump details
            System.err.printf("Expected %d frame(s)%n",expectedCount);
            System.err.printf("But actually captured %d frame(s)%n",frames.size());
            int i = 0;
            for (Frame frame : frames)
            {
                System.err.printf(" [%d] Frame[%s] - %s%n", i++, 
                        OpCode.name(frame.getOpCode()),
                        BufferUtil.toDetailString(frame.getPayload()));
            }
        }
        Assert.assertThat("Captured frame count",frames.size(),is(expectedCount));
    }

    public void assertHasErrors(Class<? extends WebSocketException> errorType, int expectedCount)
    {
        Assert.assertThat(errorType.getSimpleName(),getErrorCount(errorType),is(expectedCount));
    }

    public void assertHasFrame(byte op)
    {
        Assert.assertThat(OpCode.name(op),getFrameCount(op),greaterThanOrEqualTo(1));
    }

    public void assertHasFrame(byte op, int expectedCount)
    {
        String msg = String.format("%s frame count",OpCode.name(op));
        Assert.assertThat(msg,getFrameCount(op),is(expectedCount));
    }

    public void assertHasNoFrames()
    {
        Assert.assertThat("Frame count",frames.size(),is(0));
    }

    public void assertNoErrors()
    {
        Assert.assertThat("Error count",errors.size(),is(0));
    }

    public void clear()
    {
        frames.clear();
    }

    public void dump()
    {
        System.err.printf("Captured %d incoming frames%n",frames.size());
        int i = 0;
        for (Frame frame : frames)
        {
            System.err.printf("[%3d] %s%n",i++,frame);
            System.err.printf("          payload: %s%n",BufferUtil.toDetailString(frame.getPayload()));
        }
    }

    public int getErrorCount(Class<? extends Throwable> errorType)
    {
        int count = 0;
        for (Throwable error : errors)
        {
            if (errorType.isInstance(error))
            {
                count++;
            }
        }
        return count;
    }

    public Queue<Throwable> getErrors()
    {
        return errors;
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

    public Queue<WebSocketFrame> getFrames()
    {
        return frames;
    }

    @Override
    public void incomingError(Throwable e)
    {
        LOG.debug(e);
        errors.add(e);
    }

    @Override
    public void incomingFrame(Frame frame)
    {
        WebSocketFrame copy = WebSocketFrame.copy(frame);
        // TODO: might need to make this optional (depending on use by client vs server tests)
        // Assert.assertThat("frame.masking must be set",frame.isMasked(),is(true));
        frames.add(copy);
    }

    public int size()
    {
        return frames.size();
    }
}
