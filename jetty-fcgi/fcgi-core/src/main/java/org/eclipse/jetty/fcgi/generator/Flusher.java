//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
import java.util.Queue;

import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.ConcurrentArrayQueue;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class Flusher
{
    private static final Logger LOG = Log.getLogger(Flusher.class);

    private final Queue<Generator.Result> queue = new ConcurrentArrayQueue<>();
    private final IteratingCallback flushCallback = new FlushCallback();
    private final EndPoint endPoint;

    public Flusher(EndPoint endPoint)
    {
        this.endPoint = endPoint;
    }

    public void flush(Generator.Result... results)
    {
        for (Generator.Result result : results)
            queue.offer(result);
        flushCallback.iterate();
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
            Generator.Result result = queue.poll();
            if (result == null)
            {
                // No more writes to do, return.
                return Action.IDLE;
            }

            // Attempt to gather another result.
            // Most often there is another result in the
            // queue so this is a real optimization because
            // it sends both results in just one TCP packet.
            Generator.Result other = queue.poll();
            if (other != null)
                result = result.join(other);

            active = result;
            ByteBuffer[] buffers = result.getByteBuffers();
            endPoint.write(this, buffers);
            return Action.SCHEDULED;
        }

        @Override
        protected void completed()
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
        public void failed(Throwable x)
        {
            if (active != null)
                active.failed(x);
            active = null;

            while (true)
            {
                Generator.Result result = queue.poll();
                if (result == null)
                    break;
                result.failed(x);
            }

            super.failed(x);
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
            LOG.debug("Shutting down {}", endPoint);
            endPoint.shutdownOutput();
        }
    }
}
