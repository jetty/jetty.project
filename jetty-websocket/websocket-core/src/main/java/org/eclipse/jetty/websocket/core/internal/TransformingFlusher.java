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

package org.eclipse.jetty.websocket.core.internal;

import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler.Configuration;

/**
 * Used to split large data frames into multiple frames below the maxFrameSize.
 */
public abstract class TransformingFlusher
{
    private static final Logger LOG = Log.getLogger(TransformingFlusher.class);

    private final Queue<FrameEntry> entries = new ArrayDeque<>();
    private final IteratingCallback flusher = new Flusher();
    private final Configuration configuration;
    private boolean canEnqueue = true;

    public TransformingFlusher(Configuration configuration)
    {
        this.configuration = configuration;
    }

    protected abstract boolean transform(Frame frame, Callback callback, boolean batch, boolean first);

    public void sendFrame(Frame frame, Callback callback, boolean batch)
    {
        FrameEntry entry = new FrameEntry(frame, callback, batch);
        if (LOG.isDebugEnabled())
            LOG.debug("Queuing {}", entry);

        boolean enqueued = false;
        synchronized (this)
        {
            if (canEnqueue)
                enqueued = entries.offer(entry);
        }

        if (enqueued)
            flusher.iterate();
        else
            notifyCallbackFailure(callback, new ClosedChannelException());
    }

    private void onFailure(Throwable t)
    {
        synchronized (this)
        {
            canEnqueue = false;
        }

        for (FrameEntry entry : entries)
            notifyCallbackFailure(entry.callback, t);
        entries.clear();
    }

    private FrameEntry pollEntry()
    {
        synchronized (this)
        {
            return entries.poll();
        }
    }

    private class Flusher extends IteratingCallback implements Callback
    {
        private FrameEntry current;
        private boolean finished = true;

        @Override
        protected Action process()
        {
            if (finished)
            {
                current = pollEntry();
                LOG.debug("Processing {}", current);
                if (current == null)
                    return Action.IDLE;
            }

            finished = transform(current.frame, this, current.batch, finished);
            return Action.SCHEDULED;
        }

        @Override
        protected void onCompleteSuccess()
        {
            // This IteratingCallback never completes.
        }

        @Override
        protected void onCompleteFailure(Throwable t)
        {
            onFailure(t);
        }

        @Override
        public void succeeded()
        {
            // Notify first then call succeeded(), otherwise
            // write callbacks may be invoked out of order.
            if (finished)
                notifyCallbackSuccess(current.callback);
            super.succeeded();
        }

        @Override
        public void failed(Throwable cause)
        {
            notifyCallbackFailure(current.callback, cause);
            super.failed(cause);
        }
    }

    private static void notifyCallbackSuccess(Callback callback)
    {
        try
        {
            if (callback != null)
                callback.succeeded();
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Exception while notifying success of callback " + callback, x);
        }
    }

    private static void notifyCallbackFailure(Callback callback, Throwable failure)
    {
        try
        {
            if (callback != null)
                callback.failed(failure);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Exception while notifying failure of callback " + callback, x);
        }
    }
}
