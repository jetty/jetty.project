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

import java.util.ArrayDeque;
import java.util.Queue;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.Frame;

public abstract class TransformingFlusher
{
    private final Logger LOG = Log.getLogger(this.getClass());

    private final Queue<FrameEntry> entries = new ArrayDeque<>();
    private final IteratingCallback flusher = new Flusher();
    private boolean finished = true;
    private Throwable failure;

    /**
     * Called when a frame is ready to be transformed.
     * @param frame the frame to transform.
     * @param callback used to signal to start processing again.
     * @param batch whether this frame can be batched.
     */
    protected abstract void onFrame(Frame frame, Callback callback, boolean batch);

    /**
     * Called multiple times to transform the frame given in {@link TransformingFlusher#onFrame(Frame, Callback, boolean)}.
     */
    protected abstract void transform();

    /**
     * Called to indicate that you have finished transforming this frame and are ready to receive a new one
     * after the next the callback is succeeded.
     * @param finished
     */
    public final void setFinished(boolean finished)
    {
        this.finished = finished;
    }

    public final void sendFrame(Frame frame, Callback callback, boolean batch)
    {
        FrameEntry entry = new FrameEntry(frame, callback, batch);
        if (LOG.isDebugEnabled())
            LOG.debug("Queuing {}", entry);

        boolean enqueued = false;
        synchronized (this)
        {
            if (failure == null)
            {
                enqueued = entries.add(entry);
            }
        }

        if (enqueued)
            flusher.iterate();
        else
            notifyCallbackFailure(callback, failure);
    }

    private void onFailure(Throwable t)
    {
        synchronized (this)
        {
            if (failure == null)
                failure = t;
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

        @Override
        protected Action process()
        {
            if (finished)
            {
                current = pollEntry();
                if (LOG.isDebugEnabled())
                    LOG.debug("Processing {}", current);
                if (current == null)
                    return Action.IDLE;
            }

            if (LOG.isDebugEnabled())
                LOG.debug("Processing {}", current);

            onFrame(current.frame, this, current.batch);
            if (!finished)
                transform();
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
            if (LOG.isDebugEnabled())
                LOG.debug("succeeded");

            if (finished)
                notifyCallbackSuccess(current.callback);
            super.succeeded();
        }

        @Override
        public void failed(Throwable cause)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("failed {}", cause);

            notifyCallbackFailure(current.callback, cause);
            super.failed(cause);
        }
    }

    private void notifyCallbackSuccess(Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("notifyCallbackSuccess {}", callback);

        try
        {
            if (callback != null)
                callback.succeeded();
        }
        catch (Throwable x)
        {
            LOG.warn("Exception while notifying success of callback " + callback, x);
        }
    }

    private void notifyCallbackFailure(Callback callback, Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("notifyCallbackFailure {} {}", callback, failure);

        try
        {
            if (callback != null)
                callback.failed(failure);
        }
        catch (Throwable x)
        {
            LOG.warn("Exception while notifying failure of callback " + callback, x);
        }
    }
}
