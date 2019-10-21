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

import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.Queue;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.FrameHandler.Configuration;
import org.eclipse.jetty.websocket.core.OpCode;

/**
 * Fragment Extension
 */
public abstract class FragmentingFlusher
{
    private static final Logger LOG = Log.getLogger(FragmentingFlusher.class);

    private final Queue<FrameEntry> entries = new ArrayDeque<>();
    private final IteratingCallback flusher = new Flusher();
    private final Configuration configuration;
    private boolean canEnqueue = true;

    public FragmentingFlusher(Configuration configuration)
    {
        this.configuration = configuration;
    }

    abstract void forwardFrame(Frame frame, Callback callback, boolean batch);

    public void sendFrame(Frame frame, Callback callback, boolean batch)
    {
        boolean enqueued = false;
        synchronized (this)
        {
            if (canEnqueue)
                enqueued = entries.offer(new FrameEntry(frame, callback, batch));
        }

        if (enqueued)
            flusher.iterate();
        else
            notifyCallbackFailure(callback, new ClosedChannelException());
    }

    protected void onFailure(Throwable t)
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

                finished = false;
                fragment(current, true);
            }
            else
            {
                fragment(current, false);
            }
            return Action.SCHEDULED;
        }

        private void fragment(FrameEntry entry, boolean first)
        {
            Frame frame = entry.frame;
            ByteBuffer payload = frame.getPayload();
            int remaining = payload.remaining();
            long maxFrameSize = configuration.getMaxFrameSize();
            int fragmentSize = (int)Math.min(remaining, maxFrameSize);

            boolean continuation = (frame.getOpCode() == OpCode.CONTINUATION) || !first;
            Frame fragment = new Frame(continuation ? OpCode.CONTINUATION : frame.getOpCode());
            finished = (maxFrameSize <= 0 || remaining <= maxFrameSize);
            fragment.setFin(frame.isFin() && finished);

            // If we don't need to fragment just forward with original payload.
            if (finished)
            {
                fragment.setPayload(frame.getPayload());
                forwardFrame(fragment, this, entry.batch);
                return;
            }

            // Slice the fragmented payload from the buffer.
            int limit = payload.limit();
            int newLimit = payload.position() + fragmentSize;
            payload.limit(newLimit);
            ByteBuffer payloadFragment = payload.slice();
            payload.limit(limit);
            fragment.setPayload(payloadFragment);
            payload.position(newLimit);
            if (LOG.isDebugEnabled())
                LOG.debug("Fragmented {}->{}", frame, fragment);

            forwardFrame(fragment, this, entry.batch);
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
