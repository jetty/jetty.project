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

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.common.AbstractWholeMessageHandler;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.frames.CloseFrame;

public class FrameHandlerTracker extends AbstractWholeMessageHandler
{
    public CountDownLatch openLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);
    public AtomicReference<CloseStatus> closeDetail = new AtomicReference<>();
    public AtomicReference<Throwable> error = new AtomicReference<>();
    public BlockingQueue<String> messageQueue = new LinkedBlockingDeque<>();
    public BlockingQueue<ByteBuffer> bufferQueue = new LinkedBlockingDeque<>();
    public BlockingQueue<String> events = new LinkedBlockingDeque<>();

    public void addEvent(String format, Object ... args)
    {
        events.offer(String.format(format, args));
    }

    @Override
    public void onOpen(Channel channel) throws Exception
    {
        super.onOpen(channel);
        openLatch.countDown();
    }

    @Override
    public void onClose(Frame frame, Callback callback)
    {
        super.onClose(frame, callback);
        closeDetail.compareAndSet(null, CloseFrame.toCloseStatus(frame.getPayload()));
        closeLatch.countDown();
    }

    @Override
    public void onError(Throwable cause) throws Exception
    {
        super.onError(cause);
        error.compareAndSet(null, cause);
    }

    @Override
    public void onWholeText(String wholeMessage, Callback callback)
    {
        messageQueue.offer(wholeMessage);
        callback.succeeded();
    }

    @Override
    public void onWholeBinary(ByteBuffer wholeMessage, Callback callback)
    {
        bufferQueue.offer(copyOf(wholeMessage));
        callback.succeeded();
    }
}
