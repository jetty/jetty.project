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

package org.eclipse.jetty.websocket.javax.tests.framehandlers;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.MessageHandler;

public class FrameHandlerTracker extends MessageHandler
{
    public CountDownLatch openLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public AtomicReference<CloseStatus> closeDetail = new AtomicReference<>();
    public AtomicReference<Throwable> error = new AtomicReference<>();
    public BlockingQueue<String> messageQueue = new LinkedBlockingDeque<>();
    public BlockingQueue<ByteBuffer> bufferQueue = new LinkedBlockingDeque<>();
    public BlockingQueue<String> events = new LinkedBlockingDeque<>();

    public void addEvent(String format, Object... args)
    {
        events.offer(String.format(format, args));
    }

    @Override
    public void onOpen(CoreSession coreSession, Callback callback)
    {
        super.onOpen(coreSession, Callback.from(callback, () -> openLatch.countDown()));
    }

    @Override
    public void onText(String wholeMessage, Callback callback)
    {
        messageQueue.offer(wholeMessage);
        callback.succeeded();
    }

    @Override
    public void onBinary(ByteBuffer wholeMessage, Callback callback)
    {
        bufferQueue.offer(BufferUtil.copy(wholeMessage));
        callback.succeeded();
    }

    @Override
    public void onError(Throwable cause, Callback callback)
    {
        super.onError(cause, Callback.from(callback, () -> error.compareAndSet(null, cause)));
    }

    @Override
    public void onClosed(CloseStatus closeStatus, Callback callback)
    {
        super.onClosed(closeStatus, Callback.from(callback, () ->
        {
            closeDetail.compareAndSet(null, closeStatus);
            closeLatch.countDown();
        }));
    }
}
