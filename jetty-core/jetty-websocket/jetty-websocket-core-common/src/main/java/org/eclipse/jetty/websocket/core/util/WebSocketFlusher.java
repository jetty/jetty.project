//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.StaticException;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.websocket.core.OutgoingEntry;
import org.eclipse.jetty.websocket.core.OutgoingFrames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is used to iteratively transform or process a frame into one or more other frames.
 * When a frame is ready to be processed {@link #onFrame(OutgoingEntry, boolean)} is called.
 * Subsequent calls to {@link #onFrame(OutgoingEntry, boolean)} are after each time the entry is succeeded
 * until one of these calls returns true to indicate they are done processing the frame and are ready to receive a new one.
 */
public abstract class WebSocketFlusher implements OutgoingFrames
{
    private static final Throwable SENTINEL_CLOSE_EXCEPTION = new StaticException("Closed");
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    private final AutoLock _lock = new AutoLock();
    private final Queue<OutgoingEntry> _entries = new ArrayDeque<>();
    private final IteratingCallback _flusher = new Flusher();
    private Throwable _failure;

    /**
     * Called when a frame is ready to be transformed.
     * @param entry the entry containing the frame to be transformed.
     * @param first true if this is the first time this entry is being processed.
     * @return true to indicate that you have finished transforming this entry.
     */
    protected abstract boolean onFrame(OutgoingEntry entry, boolean first);

    /**
     * Called when the flusher has failed and {@link #onFrame(OutgoingEntry, boolean)} will never be called again.
     */
    protected void onCompleteFailure(Throwable cause)
    {
    }

    @Override
    public final void sendFrame(OutgoingEntry entry)
    {
        if (log.isDebugEnabled())
            log.debug("Queuing {}", entry);

        Throwable failure = null;
        try (AutoLock ignored = _lock.lock())
        {
            if (_failure == null)
                _entries.add(entry);
            else
                failure = _failure;
        }

        if (failure == null)
            _flusher.iterate();
        else
            notifyCallbackFailure(entry, failure);
    }

    /**
     * Used to close this flusher when there is no explicit failure.
     */
    public void closeFlusher()
    {
        failFlusher(SENTINEL_CLOSE_EXCEPTION);
    }

    /**
     * Used to fail this flusher possibly from an external event such as a callback.
     * @param t the failure.
     */
    public void failFlusher(Throwable t)
    {
        boolean failed = false;
        try (AutoLock ignored = _lock.lock())
        {
            if (_failure == null)
            {
                _failure = t;
                failed = true;
            }
            else
            {
                _failure.addSuppressed(t);
            }
        }

        if (failed)
            _flusher.abort(t);
    }

    private class Flusher extends IteratingCallback implements Callback
    {
        private boolean _completed = false;
        private OutgoingEntry _current;

        @Override
        protected Action process() throws Throwable
        {
            boolean first = false;
            try (AutoLock ignored = _lock.lock())
            {
                if (_failure != null)
                    throw _failure;

                if (_current == null)
                {
                    first = true;
                    _current = _entries.poll();
                }
            }

            if (_current == null)
                return Action.IDLE;

            if (log.isDebugEnabled())
                log.debug("onFrame {}", _current);

            _completed = onFrame(new OutgoingEntry.Builder(_current).callback(this).build(), first);
            return Action.SCHEDULED;
        }

        @Override
        protected void onSuccess()
        {
            if (_completed)
            {
                notifyCallbackSuccess(_current);
                _current = null;
            }
        }

        @Override
        protected void onCompleteFailure(Throwable t)
        {
            if (log.isDebugEnabled())
                log.debug("onCompleteFailure {}", t.toString());

            List<OutgoingEntry> entries;
            try (AutoLock ignored = _lock.lock())
            {
                if (_failure == null)
                    _failure = t;
                entries = new ArrayList<>(_entries);
                _entries.clear();
            }

            if (_current != null)
            {
                notifyCallbackFailure(_current, t);
                _current = null;
            }

            for (OutgoingEntry entry : entries)
                notifyCallbackFailure(entry, t);

            // Allow any subclass to clean up internal state on failure.
            WebSocketFlusher.this.onCompleteFailure(t);
        }
    }

    private void notifyCallbackSuccess(OutgoingEntry entry)
    {
        if (log.isDebugEnabled())
            log.debug("notifyCallbackSuccess {}", entry);

        try
        {
            entry.getCallback().succeeded();
        }
        catch (Throwable x)
        {
            log.warn("Exception while notifying success of entry {}", entry, x);
        }
    }

    private void notifyCallbackFailure(OutgoingEntry entry, Throwable failure)
    {
        if (log.isDebugEnabled())
            log.debug("notifyCallbackFailure {} {}", entry, failure.toString());

        try
        {
            entry.getCallback().failed(failure);
        }
        catch (Throwable x)
        {
            log.warn("Exception while notifying failure of entry {}", entry, x);
        }
    }
}
