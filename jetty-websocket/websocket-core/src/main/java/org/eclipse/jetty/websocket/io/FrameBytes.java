// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.io;

import java.nio.ByteBuffer;
import java.nio.channels.InterruptedByTimeoutException;
import java.util.concurrent.ScheduledFuture;

import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

public abstract class FrameBytes<C> implements Callback<C>, Runnable
{
    private final static Logger LOG = Log.getLogger(FrameBytes.class);
    protected final AbstractWebSocketConnection connection;
    protected final Callback<C> callback;
    protected final C context;
    protected final WebSocketFrame frame;
    // Task used to timeout the bytes
    protected volatile ScheduledFuture<?> task;

    protected FrameBytes(AbstractWebSocketConnection connection, Callback<C> callback, C context, WebSocketFrame frame)
    {
        this.connection = connection;
        this.callback = callback;
        this.context = context;
        this.frame = frame;
    }

    private void cancelTask()
    {
        ScheduledFuture<?> task = this.task;
        if (task != null)
        {
            task.cancel(false);
        }
    }

    @Override
    public void completed(C context)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("completed({}) - {}",context,this.getClass().getName());
        }
        cancelTask();
        connection.complete(this);
        callback.completed(context);
    }

    @Override
    public void failed(C context, Throwable x)
    {
        if (x instanceof EofException)
        {
            // Abbreviate the EofException
            LOG.warn("failed(" + context + ") - " + EofException.class);
        }
        else
        {
            LOG.warn("failed(" + context + ")",x);
        }
        cancelTask();
        callback.failed(context,x);
    }

    public abstract ByteBuffer getByteBuffer();

    @Override
    public void run()
    {
        // If this occurs we had a timeout!
        connection.close();
        failed(context, new InterruptedByTimeoutException());
    }

    @Override
    public String toString()
    {
        return frame.toString();
    }
}