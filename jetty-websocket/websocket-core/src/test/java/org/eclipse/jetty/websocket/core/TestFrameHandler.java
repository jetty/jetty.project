//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class TestFrameHandler implements SynchronousFrameHandler
{
    private static Logger LOG = Log.getLogger(TestFrameHandler.class);

    protected CoreSession coreSession;
    public BlockingQueue<Frame> receivedFrames = new BlockingArrayQueue<>();
    protected Throwable failure;

    public CountDownLatch open = new CountDownLatch(1);
    public CountDownLatch error = new CountDownLatch(1);
    public CountDownLatch closed = new CountDownLatch(1);

    public CoreSession getCoreSession()
    {
        return coreSession;
    }

    public BlockingQueue<Frame> getFrames()
    {
        return receivedFrames;
    }

    public Throwable getError()
    {
        return failure;
    }

    @Override
    public void onOpen(CoreSession coreSession)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onOpen {}", coreSession);
        this.coreSession = coreSession;
        open.countDown();
    }

    @Override
    public void onFrame(Frame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onFrame: " + OpCode.name(frame.getOpCode()) + ":" + BufferUtil.toDetailString(frame.getPayload()));
        receivedFrames.offer(Frame.copy(frame));
    }

    @Override
    public void onClosed(CloseStatus closeStatus)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onClosed {}", closeStatus);
        closed.countDown();
    }

    @Override
    public void onError(Throwable cause)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onError {} ", cause == null ? null : cause.toString());
        failure = cause;
        error.countDown();
    }

    public void sendText(String text)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("sendText {} ", text);
        Frame frame = new Frame(OpCode.TEXT, text);
        getCoreSession().sendFrame(frame, Callback.NOOP, false);
    }

    public void sendFrame(Frame frame)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("sendFrame {} ", frame);
        getCoreSession().sendFrame(frame, Callback.NOOP, false);
    }

    public void sendClose()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("sendClose");
        Frame frame = new Frame(OpCode.CLOSE);
        getCoreSession().sendFrame(frame, Callback.NOOP, false);
    }
}
