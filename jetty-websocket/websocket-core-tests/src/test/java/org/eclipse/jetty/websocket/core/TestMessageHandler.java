//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
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
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.websocket.core.internal.AbstractMessageHandler;
import org.eclipse.jetty.websocket.core.util.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestMessageHandler extends MessageHandler
{
    protected static final Logger LOG = LoggerFactory.getLogger(TestMessageHandler.class);

    public CoreSession coreSession;
    public BlockingQueue<String> textMessages = new BlockingArrayQueue<>();
    public BlockingQueue<ByteBuffer> binaryMessages = new BlockingArrayQueue<>();
    public CloseStatus closeStatus;
    public volatile Throwable error;
    public CountDownLatch openLatch = new CountDownLatch(1);
    public CountDownLatch errorLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);

    @Override
    public void onOpen(CoreSession coreSession, Callback callback)
    {
        super.onOpen(coreSession, callback);
        this.coreSession = coreSession;
        openLatch.countDown();
    }

    @Override
    public void onError(Throwable cause, Callback callback)
    {
        super.onError(cause, callback);
        error = cause;
        errorLatch.countDown();
    }

    @Override
    public void onClosed(CloseStatus closeStatus, Callback callback)
    {
        super.onClosed(closeStatus, callback);
        this.closeStatus = closeStatus;
        closeLatch.countDown();
    }

    @Override
    protected void onText(String message, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onText {}", message);
        textMessages.offer(message);
        callback.succeeded();
    }

    @Override
    protected void onBinary(ByteBuffer message, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("onBinary {}", message);
        binaryMessages.offer(message);
        callback.succeeded();
    }

    /**
     * Send a sequence of Strings as a sequences for fragmented text frame.
     * Sending a large message in fragments can reduce memory overheads as only a
     * single fragment need be converted to bytes
     *
     * @param callback The callback to call when the send is complete
     * @param batch    The batch mode to send the frames in.
     * @param parts    The parts of the message.
     */
    public static void sendText(AbstractMessageHandler messageHandler, Callback callback, boolean batch, final String... parts)
    {
        if (parts == null || parts.length == 0)
        {
            callback.succeeded();
            return;
        }

        if (parts.length == 1)
        {
            messageHandler.sendText(parts[0], callback, batch);
            return;
        }

        new IteratingNestedCallback(callback)
        {
            int i = 0;

            @Override
            protected Action process() throws Throwable
            {
                if (i + 1 > parts.length)
                    return Action.SUCCEEDED;

                String part = parts[i++];
                messageHandler.getCoreSession().sendFrame(new Frame(
                    i == 1 ? OpCode.TEXT : OpCode.CONTINUATION,
                    i == parts.length, part), this, batch);
                return Action.SCHEDULED;
            }
        }.iterate();
    }

    /**
     * Send a sequence of ByteBuffers as a sequences for fragmented text frame.
     *
     * @param callback The callback to call when the send is complete
     * @param batch    The batch mode to send the frames in.
     * @param parts    The parts of the message.
     */
    public static void sendBinary(AbstractMessageHandler messageHandler, Callback callback, boolean batch, final ByteBuffer... parts)
    {
        if (parts == null || parts.length == 0)
        {
            callback.succeeded();
            return;
        }

        if (parts.length == 1)
        {
            messageHandler.sendBinary(parts[0], callback, batch);
            return;
        }

        new IteratingNestedCallback(callback)
        {
            int i = 0;

            @Override
            protected Action process() throws Throwable
            {
                if (i + 1 > parts.length)
                    return Action.SUCCEEDED;

                ByteBuffer part = parts[i++];
                messageHandler.getCoreSession().sendFrame(new Frame(
                    i == 1 ? OpCode.BINARY : OpCode.CONTINUATION,
                    i == parts.length, part), this, batch);
                return Action.SCHEDULED;
            }
        }.iterate();
    }
}
