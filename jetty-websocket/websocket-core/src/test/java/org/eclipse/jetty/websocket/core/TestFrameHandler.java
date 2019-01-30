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
    private static Logger LOG = Log.getLogger(SynchronousFrameHandler.class);

    private CoreSession session;

    public BlockingQueue<Frame> receivedFrames = new BlockingArrayQueue<>();
    public CountDownLatch closed = new CountDownLatch(1);

    public CoreSession getCoreSession()
    {
        return session;
    }

    public BlockingQueue<Frame> getFrames()
    {
        return receivedFrames;
    }

    @Override
    public void onOpen(CoreSession coreSession)
    {
        LOG.info("onOpen {}", coreSession);
        this.session = coreSession;
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        LOG.info("onFrame: " + OpCode.name(frame.getOpCode()) + ":" + BufferUtil.toDetailString(frame.getPayload()));
        receivedFrames.offer(Frame.copy(frame));
        callback.succeeded();
    }

    @Override
    public void onClosed(CloseStatus closeStatus)
    {
        LOG.info("onClosed {}", closeStatus);
        closed.countDown();
    }

    @Override
    public void onError(Throwable cause)
    {
        LOG.info("onError {} ", cause == null?null:cause.toString());
    }

    public void sendText(String text)
    {
        Frame frame = new Frame(OpCode.TEXT);
        frame.setFin(true);
        frame.setPayload(text);

        getCoreSession().sendFrame(frame, Callback.NOOP, false);
    }
}
