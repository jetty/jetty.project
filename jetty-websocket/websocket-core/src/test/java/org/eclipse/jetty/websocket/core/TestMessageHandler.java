//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestMessageHandler extends MessageHandler
{
    protected static final Logger LOG = LoggerFactory.getLogger(TestMessageHandler.class);

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
