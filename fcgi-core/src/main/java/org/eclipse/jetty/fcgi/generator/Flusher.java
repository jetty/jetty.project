//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.fcgi.generator;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ConcurrentArrayQueue;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class Flusher
{
    private static final Logger LOG = Log.getLogger(Flusher.class);

    private final Queue<Generator.Result> queue = new ConcurrentArrayQueue<>();
    private final Callback flushCallback = new FlushCallback();
    private final EndPoint endPoint;
    private boolean flushing;

    public Flusher(EndPoint endPoint)
    {
        this.endPoint = endPoint;
    }

    public void flush(Generator.Result... results)
    {
        synchronized (queue)
        {
            for (Generator.Result result : results)
                queue.offer(result);
            if (flushing)
                return;
            flushing = true;
        }
        endPoint.write(flushCallback);
    }

    public void shutdown()
    {
        flush(new ShutdownResult());
    }

    private class FlushCallback extends IteratingCallback
    {
        private Generator.Result active;

        @Override
        protected boolean process() throws Exception
        {
            // Look if other writes are needed.
            Generator.Result result;
            synchronized (queue)
            {
                if (queue.isEmpty())
                {
                    // No more writes to do, switch to non-flushing
                    flushing = false;
                    return false;
                }
                // TODO: here is where we want to gather more results to perform gathered writes
                result = queue.poll();
            }
            active = result;
            ByteBuffer[] buffers = result.getByteBuffers();
            endPoint.write(this, buffers);
            return false;
        }

        @Override
        protected void completed()
        {
            // Nothing to do, we always return false from process().
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
        public void failed(Throwable x)
        {
            if (active != null)
                active.failed(x);
            active = null;

            List<Generator.Result> pending = new ArrayList<>();
            synchronized (queue)
            {
                while (true)
                {
                    Generator.Result result = queue.poll();
                    if (result != null)
                        pending.add(result);
                    else
                        break;
                }
            }
            for (Generator.Result result : pending)
                result.failed(x);

            super.failed(x);
        }
    }

    private class ShutdownResult extends Generator.Result
    {
        private ShutdownResult()
        {
            super(null, null, null, false);
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
            LOG.debug("Shutting down {}", endPoint);
            endPoint.shutdownOutput();
        }
    }
}
