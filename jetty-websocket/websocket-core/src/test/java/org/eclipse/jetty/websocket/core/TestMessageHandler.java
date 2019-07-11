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

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class TestMessageHandler extends MessageHandler
{
    protected final Logger LOG = Log.getLogger(TestMessageHandler.class);

    public CoreSession coreSession;
    public BlockingQueue<String> textMessages = new BlockingArrayQueue<>();
    public BlockingQueue<ByteBuffer> binaryMessages = new BlockingArrayQueue<>();
    public volatile Throwable error;
    public CountDownLatch openLatch = new CountDownLatch(1);
    public CountDownLatch errorLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);

    @Override
    public void onOpen(CoreSession coreSession, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onOpen {}", coreSession);
        this.coreSession = coreSession;
        super.onOpen(coreSession, callback);
        openLatch.countDown();
    }

    @Override
    public void onFrame(Frame frame, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onFrame {}", frame);
        super.onFrame(frame, callback);
    }

    @Override
    public void onError(Throwable cause, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onError {}", cause);
        super.onError(cause, callback);
        error = cause;
        errorLatch.countDown();
    }

    @Override
    public void onClosed(CloseStatus closeStatus, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onClosed {}", closeStatus);
        super.onClosed(closeStatus, callback);
        closeLatch.countDown();
    }

    @Override
    protected void onText(String message, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onText {}", message);
        textMessages.offer(message);
    }

    @Override
    protected void onBinary(ByteBuffer message, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onBinary {}", message);
        binaryMessages.offer(message);
    }
}
