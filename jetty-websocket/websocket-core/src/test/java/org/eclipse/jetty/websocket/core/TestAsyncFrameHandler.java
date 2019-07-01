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
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class TestAsyncFrameHandler implements FrameHandler
{
    protected static final Logger LOG = Log.getLogger(TestAsyncFrameHandler.class);
    protected final String name;

    public CoreSession coreSession;
    public BlockingQueue<Frame> receivedFrames = new BlockingArrayQueue<>();
    public volatile Throwable error;
    public CountDownLatch openLatch = new CountDownLatch(1);
    public CountDownLatch errorLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);

    public TestAsyncFrameHandler()
    {
        name = TestAsyncFrameHandler.class.getSimpleName();
    }

    public TestAsyncFrameHandler(String name)
    {
        this.name = name;
    }

    @Override
    public void onOpen(CoreSession coreSession, Callback callback)
    {
        LOG.info("[{}] onOpen {}", name, coreSession);
        this.coreSession = coreSession;
        callback.succeeded();
        openLatch.countDown();
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        LOG.info("[{}] onFrame {}", name, frame);
        receivedFrames.offer(Frame.copy(frame));
        callback.succeeded();
    }

    @Override
    public void onClosed(CloseStatus closeStatus, Callback callback)
    {
        LOG.info("[{}] onClosed {}", name, closeStatus);
        closeLatch.countDown();
        callback.succeeded();
    }

    @Override
    public void onError(Throwable cause, Callback callback)
    {
        LOG.info("[{}] onError {} ", name, cause == null?null:cause.toString());
        error = cause;
        errorLatch.countDown();
        callback.succeeded();
    }

    public void sendText(String text)
    {
        LOG.info("[{}] sendText {} ", name, text);
        Frame frame = new Frame(OpCode.TEXT, text);
        coreSession.sendFrame(frame, Callback.NOOP, false);
    }

    public void sendFrame(Frame frame)
    {
        LOG.info("[{}] sendFrame {} ", name, frame);
        coreSession.sendFrame(frame, Callback.NOOP, false);
    }

    public void close()
    {
        close(CloseStatus.NORMAL, null);
    }

    public void close(int closeStatus, String reason)
    {
        sendFrame(CloseStatus.toFrame(closeStatus, reason));
    }
}
