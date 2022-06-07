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

package org.eclipse.jetty.websocket.core.internal;

import java.util.ArrayDeque;
import java.util.Queue;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.websocket.core.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is used to iteratively transform or process a frame into one or more other frames.
 * When a frame is ready to be processed {@link #onFrame(Frame, Callback, boolean)} is called.
 * Subsequent calls to {@link #transform(Callback)} are made on each callback success until one of these calls returns
 * true to indicate they are done processing the frame and are ready to receive a new one.
 * The {@link Callback} passed in to both these method must be succeeded in order to continue processing.
 */
public abstract class TransformingFlusher
{
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private final AutoLock lock = new AutoLock();
    private final Queue<FrameEntry> entries = new ArrayDeque<>();
    private final IteratingCallback flusher = new Flusher();
    private boolean finished = true;
    private Throwable failure;

    /**
     * Called when a frame is ready to be transformed.
     * @param frame the frame to transform.
     * @param callback used to signal to start processing again.
     * @param batch whether this frame can be batched.
     * @return true to indicate that you have finished transforming this frame.
     */
    protected abstract boolean onFrame(Frame frame, Callback callback, boolean batch);

    /**
     * Called to transform the frame given in {@link TransformingFlusher#onFrame(Frame, Callback, boolean)}.
     * This method is called on each callback success until it returns true.
     * If the call to {@link #onFrame(Frame, Callback, boolean)} returns true then this method will not be called.
     * @param callback used to signal to start processing again.
     * @return true to indicate that you have finished transforming this frame.
     */
    protected abstract boolean transform(Callback callback);

    public final void sendFrame(Frame frame, Callback callback, boolean batch)
    {
        FrameEntry entry = new FrameEntry(frame, callback, batch);
        if (log.isDebugEnabled())
            log.debug("Queuing {}", entry);

        boolean enqueued = false;
        try (AutoLock l = lock.lock())
        {
            if (failure == null)
                enqueued = entries.add(entry);
        }

        if (enqueued)
            flusher.iterate();
        else
            notifyCallbackFailure(callback, failure);
    }

    /**
     * Used to fail this flusher possibly from an external event such as a callback.
     * @param t the failure.
     */
    public void failFlusher(Throwable t)
    {
        boolean failed = false;
        try (AutoLock l = lock.lock())
        {
            if (failure == null)
            {
                failure = t;
                failed = true;
            }
        }

        if (failed)
        {
            flusher.failed(t);
            flusher.iterate();
        }
    }

    private void onFailure(Throwable t)
    {
        try (AutoLock l = lock.lock())
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
        try (AutoLock l = lock.lock())
        {
            return entries.poll();
        }
    }

    private class Flusher extends IteratingCallback implements Callback
    {
        private FrameEntry current;

        @Override
        protected Action process() throws Throwable
        {
            try (AutoLock l = lock.lock())
            {
                if (failure != null)
                    throw failure;
            }

            if (finished)
            {
                if (current != null)
                    notifyCallbackSuccess(current.callback);

                current = pollEntry();
                if (current == null)
                    return Action.IDLE;

                if (log.isDebugEnabled())
                    log.debug("onFrame {}", current);

                finished = onFrame(current.frame, this, current.batch);
                return Action.SCHEDULED;
            }

            if (log.isDebugEnabled())
                log.debug("transform {}", current);

            finished = transform(this);
            return Action.SCHEDULED;
        }

        @Override
        protected void onCompleteFailure(Throwable t)
        {
            if (log.isDebugEnabled())
                log.debug("onCompleteFailure {}", t.toString());

            if (current != null)
            {
                notifyCallbackFailure(current.callback, t);
                current = null;
            }
            onFailure(t);
        }
    }

    private void notifyCallbackSuccess(Callback callback)
    {
        if (log.isDebugEnabled())
            log.debug("notifyCallbackSuccess {}", callback);

        try
        {
            if (callback != null)
                callback.succeeded();
        }
        catch (Throwable x)
        {
            log.warn("Exception while notifying success of callback {}", callback, x);
        }
    }

    private void notifyCallbackFailure(Callback callback, Throwable failure)
    {
        if (log.isDebugEnabled())
            log.debug("notifyCallbackFailure {} {}", callback, failure.toString());

        try
        {
            if (callback != null)
                callback.failed(failure);
        }
        catch (Throwable x)
        {
            log.warn("Exception while notifying failure of callback {}", callback, x);
        }
    }
}
