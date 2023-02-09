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

package org.eclipse.jetty.fcgi.generator;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Flusher
{
    private static final Logger LOG = LoggerFactory.getLogger(Flusher.class);

    private final AutoLock lock = new AutoLock();
    private final Queue<Entry> queue = new ArrayDeque<>();
    private final IteratingCallback flushCallback = new FlushCallback();
    private final EndPoint endPoint;

    public Flusher(EndPoint endPoint)
    {
        this.endPoint = endPoint;
    }

    public void flush(ByteBufferPool.Accumulator accumulator, Callback callback)
    {
        offer(new Entry(accumulator, callback));
        flushCallback.iterate();
    }

    private void offer(Entry entry)
    {
        try (AutoLock ignored = lock.lock())
        {
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
        flush(null, Callback.from(() ->
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Shutting down {}", endPoint);
            endPoint.shutdownOutput();
        }));
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
            List<ByteBuffer> buffers = entry.accumulator.getByteBuffers();
            endPoint.write(this, buffers.toArray(ByteBuffer[]::new));
            return Action.SCHEDULED;
        }

        @Override
        protected void onCompleteSuccess()
        {
            // We never return Action.SUCCEEDED, so this method is never called.
            throw new IllegalStateException();
        }

        @Override
        public void succeeded()
        {
            if (active != null)
                active.succeeded();
            active = null;
            super.succeeded();
        }

        @Override
        public void onCompleteFailure(Throwable x)
        {
            if (active != null)
                active.failed(x);
            active = null;

            while (true)
            {
                Entry entry = poll();
                if (entry == null)
                    break;
                entry.failed(x);
            }
        }
    }

    private record Entry(ByteBufferPool.Accumulator accumulator, Callback callback) implements Callback
    {
        @Override
        public void succeeded()
        {
            if (accumulator != null)
                accumulator.release();
            callback.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            if (accumulator != null)
                accumulator.release();
            callback.failed(x);
        }
    }
}
