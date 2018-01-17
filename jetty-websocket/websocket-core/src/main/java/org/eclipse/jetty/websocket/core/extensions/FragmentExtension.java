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

package org.eclipse.jetty.websocket.core.extensions;


import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Queue;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.frames.DataFrame;
import org.eclipse.jetty.websocket.core.frames.OpCode;
import org.eclipse.jetty.websocket.core.io.BatchMode;

/**
 * Fragment Extension
 */
public class FragmentExtension extends AbstractExtension
{
    private static final Logger LOG = Log.getLogger(FragmentExtension.class);

    private final Queue<FrameEntry> entries = new ArrayDeque<>();
    private final IteratingCallback flusher = new Flusher();
    private int maxLength;

    @Override
    public String getName()
    {
        return "fragment";
    }

    @Override
    public void receiveFrame(Frame frame, Callback callback)
    {
        nextIncomingFrame(frame, callback);
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, BatchMode batchMode)
    {
        ByteBuffer payload = frame.getPayload();
        int length = payload != null ? payload.remaining() : 0;
        if (OpCode.isControlFrame(frame.getOpCode()) || maxLength <= 0 || length <= maxLength)
        {
            nextOutgoingFrame(frame, callback, batchMode);
            return;
        }

        FrameEntry entry = new FrameEntry(frame, callback, batchMode);
        if (LOG.isDebugEnabled())
            LOG.debug("Queuing {}", entry);
        offerEntry(entry);
        flusher.iterate();
    }

    @Override
    public void setConfig(ExtensionConfig config)
    {
        super.setConfig(config);
        maxLength = config.getParameter("maxLength", -1);
    }

    private void offerEntry(FrameEntry entry)
    {
        synchronized (this)
        {
            entries.offer(entry);
        }
    }

    private FrameEntry pollEntry()
    {
        synchronized (this)
        {
            return entries.poll();
        }
    }

    private static class FrameEntry
    {
        private final Frame frame;
        private final Callback callback;
        private final BatchMode batchMode;

        private FrameEntry(Frame frame, Callback callback, BatchMode batchMode)
        {
            this.frame = frame;
            this.callback = callback;
            this.batchMode = batchMode;
        }

        @Override
        public String toString()
        {
            return frame.toString();
        }
    }

    private class Flusher extends IteratingCallback implements Callback
    {
        private FrameEntry current;
        private boolean finished = true;

        @Override
        protected Action process() throws Exception
        {
            if (finished)
            {
                current = pollEntry();
                LOG.debug("Processing {}", current);
                if (current == null)
                    return Action.IDLE;
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
            int length = Math.min(remaining, maxLength);
            finished = length == remaining;

            boolean continuation = frame.getType().isContinuation() || !first;
            DataFrame fragment = new DataFrame(frame, continuation);
            boolean fin = frame.isFin() && finished;
            fragment.setFin(fin);

            int limit = payload.limit();
            int newLimit = payload.position() + length;
            payload.limit(newLimit);
            ByteBuffer payloadFragment = payload.slice();
            payload.limit(limit);
            fragment.setPayload(payloadFragment);
            if (LOG.isDebugEnabled())
                LOG.debug("Fragmented {}->{}", frame, fragment);
            payload.position(newLimit);

            nextOutgoingFrame(fragment, this, entry.batchMode);
        }

        @Override
        protected void onCompleteSuccess()
        {
            // This IteratingCallback never completes.
        }
        
        @Override
        protected void onCompleteFailure(Throwable x)
        {
            // This IteratingCallback never fails.
            // The callback are those provided by WriteCallback (implemented
            // below) and even in case of writeFailed() we call succeeded().
        }

        @Override
        public void succeeded()
        {
            // Notify first then call succeeded(), otherwise
            // write callbacks may be invoked out of order.
            notifyCallbackSuccess(current.callback);
            super.succeeded();
        }
    
        @Override
        public void failed(Throwable cause)
        {
            // Notify first, the call succeeded() to drain the queue.
            // We don't want to call failed(x) because that will put
            // this flusher into a final state that cannot be exited,
            // and the failure of a frame may not mean that the whole
            // connection is now invalid.
            notifyCallbackFailure(current.callback, cause);
            succeeded();
        }

        private void notifyCallbackSuccess(Callback callback)
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

        private void notifyCallbackFailure(Callback callback, Throwable failure)
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
}
