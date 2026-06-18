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

package org.eclipse.jetty.fcgi.generator;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Flusher
{
    private static final Logger LOG = LoggerFactory.getLogger(Flusher.class);

    private final AutoLock lock = new AutoLock();
    private final Queue<Entry> queue = new ArrayDeque<>();
    private final FlushCallback flushCallback = new FlushCallback();
    private final EndPoint endPoint;

    public Flusher(EndPoint endPoint)
    {
        this.endPoint = endPoint;
    }

    public Callback cancel(Throwable cause)
    {
        // Cancel the IteratingCallback and take the nest Callback
        CancelSendException cancelSendException = new CancelSendException(cause);
        if (!flushCallback.abort(cancelSendException))
            return Callback.NOOP;

        // We now know that we aborted this ICB with the CSE above, so onAbort will eventually be called
        // in a serialized context and the callback will be set on the CSE.

        // If a write operation has been scheduled cancel it and fail its callback, otherwise complete ourselves
        Callback writeCallback = endPoint.cancelWrite(cause);

        if (writeCallback == null)
            // There was no write in operation, so we can complete the CSE ourselves
            cancelSendException.complete();
        else
            // The write was cancelled and the callback is this ICB, so failing it will call onCompleted
            writeCallback.failed(cause);

        // wait for the cancellation to be complete and the callback to be set by onAbort.
        // This should never block indefinitely, as onAborted only waits for active states like PROCESSING to complete.
        return cancelSendException.join();
    }

    public void flush(ReadableBuffer buffer, Callback callback)
    {
        offer(new Entry(buffer, callback));
        flushCallback.iterate();
    }

    private void offer(Entry entry)
    {
        try (AutoLock ignored = lock.lock())
        {
            entry.buffer().retain();
            queue.offer(entry);
        }
    }

    private Entry poll()
    {
        try (AutoLock ignored = lock.lock())
        {
            return queue.poll();
        }
    }

    public void shutdown()
    {
        flush(ReadableBuffer.EMPTY, Callback.from(() ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Shutting down {}", endPoint);
            endPoint.shutdownOutput();
        }));
    }

    private static class CancelSendException extends IOException
    {
        private final CountDownLatch _complete = new CountDownLatch(2);
        private Callback _callback;

        public CancelSendException(Throwable cause)
        {
            super(cause);
        }

        public void complete()
        {
            _complete.countDown();
        }

        public Callback join()
        {
            try
            {
                _complete.await();
            }
            catch (InterruptedException x)
            {
                Throwable cause = getCause();
                if (cause == null)
                    throw new RuntimeException(x);
                ExceptionUtil.addSuppressedIfNotAssociated(cause, x);
                throw ExceptionUtil.asRuntimeException(cause);
            }

            return _callback;
        }

        public void setCallback(Callback callback)
        {
            _callback = callback;
            _complete.countDown();
        }
    }

    private class FlushCallback extends IteratingCallback
    {
        private Entry active;

        @Override
        protected Action process() throws Exception
        {
            // Look if other writes are needed.
            Entry entry = poll();
            if (entry == null)
            {
                // No more writes to do, return.
                return Action.IDLE;
            }

            active = entry;

            endPoint.write(entry.buffer(), this);
            return Action.SCHEDULED;
        }

        @Override
        protected void onAborted(Throwable cause)
        {
            if (cause instanceof CancelSendException cancelSend)
                cancelSend.setCallback(resetCallback());
        }

        private Callback resetCallback()
        {
            Flusher.Entry entry = active;
            active = null;
            return Callback.from(entry.callback, () -> entry.buffer().release());
        }

        @Override
        protected void onCompleted(Throwable causeOrNull)
        {
            if (causeOrNull instanceof CancelSendException cancelSendException)
                cancelSendException.complete();
            super.onCompleted(causeOrNull);
        }

        @Override
        protected void onCompleteSuccess()
        {
            // We never return Action.SUCCEEDED, so this method is never called.
            throw new IllegalStateException();
        }

        @Override
        protected void onSuccess()
        {
            if (active != null)
            {
                active.callback().succeeded();
                active.buffer().release();
                active = null;
            }
        }

        @Override
        public void onFailure(Throwable cause)
        {
            if (active != null)
                active.callback().failed(cause);
            List<Entry> entries;
            try (AutoLock ignored = lock.lock())
            {
                entries = new ArrayList<>(queue);
            }
            entries.forEach(entry -> entry.callback().failed(cause));
        }

        @Override
        protected void onCompleteFailure(Throwable cause)
        {
            if (active != null)
            {
                active.buffer().release();
                active = null;
            }
            List<Entry> entries;
            try (AutoLock ignored = lock.lock())
            {
                entries = new ArrayList<>(queue);
                queue.clear();
            }
            entries.forEach(e -> e.buffer().release());
        }

        @Override
        public InvocationType getInvocationType()
        {
            if (active == null)
                return InvocationType.NON_BLOCKING;
            return active.callback().getInvocationType();
        }
    }

    private record Entry(ReadableBuffer buffer, Callback callback)
    {
    }
}
