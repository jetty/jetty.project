//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.tests.framehandlers;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.MessageHandler;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicReference;

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
    public void onOpen(CoreSession coreSession) throws Exception
    {
        super.onOpen(coreSession);
        openLatch.countDown();
    }

    @Override
    public void onClosed(CloseStatus closeStatus)
    {
        super.onClosed(closeStatus);

        closeDetail.compareAndSet(null, closeStatus);
        closeLatch.countDown();
    }

    @Override
    public void onError(Throwable cause) throws Exception
    {
        super.onError(cause);
        error.compareAndSet(null, cause);
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
}
