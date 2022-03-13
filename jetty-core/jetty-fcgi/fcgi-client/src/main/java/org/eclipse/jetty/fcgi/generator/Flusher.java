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
import java.util.Queue;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Flusher
{
    private static final Logger LOG = LoggerFactory.getLogger(Flusher.class);

    private final AutoLock lock = new AutoLock();
    private final Queue<Generator.Result> queue = new ArrayDeque<>();
    private final IteratingCallback flushCallback = new FlushCallback();
    private final EndPoint endPoint;

    public Flusher(EndPoint endPoint)
    {
        this.endPoint = endPoint;
    }

    public void flush(Generator.Result... results)
    {
        for (Generator.Result result : results)
        {
            offer(result);
        }
        flushCallback.iterate();
    }

    private void offer(Generator.Result result)
    {
        try (AutoLock ignored = lock.lock())
        {
            queue.offer(result);
        }
    }

    private Generator.Result poll()
    {
        try (AutoLock ignored = lock.lock())
        {
            return queue.poll();
        }
    }

    public void shutdown()
    {
        flush(new ShutdownResult());
    }

    private class FlushCallback extends IteratingCallback
    {
        private Generator.Result active;

        @Override
        protected Action process() throws Exception
        {
            // Look if other writes are needed.
            Generator.Result result = poll();
            if (result == null)
            {
                // No more writes to do, return.
                return Action.IDLE;
            }

            // Attempt to gather another result.
            // Most often there is another result in the
            // queue so this is a real optimization because
            // it sends both results in just one TCP packet.
            Generator.Result other = poll();
            if (other != null)
                result = result.join(other);

            active = result;
            ByteBuffer[] buffers = result.getByteBuffers();
            endPoint.write(this, buffers);
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
                Generator.Result result = poll();
                if (result == null)
                    break;
                result.failed(x);
            }
        }
    }

    private class ShutdownResult extends Generator.Result
    {
        private ShutdownResult()
        {
            super(null, null);
        }

        @Override
        public void succeeded()
        {
            shutdown();
        }

        @Override
        public void failed(Throwable x)
        {
            shutdown();
        }

        private void shutdown()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Shutting down {}", endPoint);
            endPoint.shutdownOutput();
        }
    }
}
