//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.mux;

import static org.hamcrest.Matchers.is;

import java.util.LinkedList;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.mux.op.MuxAddChannelRequest;
import org.eclipse.jetty.websocket.mux.op.MuxAddChannelResponse;
import org.eclipse.jetty.websocket.mux.op.MuxDropChannel;
import org.eclipse.jetty.websocket.mux.op.MuxFlowControl;
import org.eclipse.jetty.websocket.mux.op.MuxNewChannelSlot;
import org.junit.Assert;

public class MuxEventCapture implements MuxParser.Listener
{
    private static final Logger LOG = Log.getLogger(MuxEventCapture.class);

    private LinkedList<MuxedFrame> frames = new LinkedList<>();
    private LinkedList<MuxControlBlock> ops = new LinkedList<>();
    private LinkedList<MuxException> errors = new LinkedList<>();

    public void assertFrameCount(int expected)
    {
        Assert.assertThat("Frame Count",frames.size(), is(expected));
    }

    public void assertHasFrame(byte opcode, long channelId, int expectedCount)
    {
        int actualCount = 0;

        for (MuxedFrame frame : frames)
        {
            if (frame.getChannelId() == channelId)
            {
                if (frame.getOpCode() == opcode)
                {
                    actualCount++;
                }
            }
        }

        Assert.assertThat("Expected Count of " + OpCode.name(opcode) + " frames on Channel ID " + channelId,actualCount,is(expectedCount));
    }

    public void assertHasOp(byte opCode, int expectedCount)
    {
        int actualCount = 0;
        for (MuxControlBlock block : ops)
        {
            if (block.getOpCode() == opCode)
            {
                actualCount++;
            }
        }
        Assert.assertThat("Op[" + opCode + "] count",actualCount,is(expectedCount));
    }

    public LinkedList<MuxedFrame> getFrames()
    {
        return frames;
    }

    public LinkedList<MuxControlBlock> getOps()
    {
        return ops;
    }

    @Override
    public void onMuxAddChannelRequest(MuxAddChannelRequest request)
    {
        ops.add(request);
    }

    @Override
    public void onMuxAddChannelResponse(MuxAddChannelResponse response)
    {
        ops.add(response);
    }

    @Override
    public void onMuxDropChannel(MuxDropChannel drop)
    {
        ops.add(drop);
    }

    @Override
    public void onMuxedFrame(MuxedFrame frame)
    {
        frames.add(new MuxedFrame(frame));
    }

    @Override
    public void onMuxException(MuxException e)
    {
        LOG.debug(e);
        errors.add(e);
    }

    @Override
    public void onMuxFlowControl(MuxFlowControl flow)
    {
        ops.add(flow);
    }

    @Override
    public void onMuxNewChannelSlot(MuxNewChannelSlot slot)
    {
        ops.add(slot);
    }

    public void reset()
    {
        frames.clear();
        ops.clear();
    }
}
