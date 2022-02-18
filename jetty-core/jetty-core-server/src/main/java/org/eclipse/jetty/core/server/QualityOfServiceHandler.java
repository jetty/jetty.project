//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.core.server;

import java.util.ArrayDeque;
import java.util.Queue;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.AutoLock;

public class QualityOfServiceHandler extends Handler.Wrapper
{
    private final AutoLock lock = new AutoLock();
    private final Queue<Entry> queue = new ArrayDeque<>();
    private int permits;

    public QualityOfServiceHandler(int permits)
    {
        this.permits = permits;
    }

    @Override
    public void accept(Request request) throws Exception
    {
        super.accept(new QualityOfServiceRequest(request));
    }

    private void release()
    {
        Entry entry;
        try (AutoLock ignored = lock.lock())
        {
            ++permits;
            entry = queue.poll();
        }
        if (entry != null)
            entry.request.execute(() -> entry.request.handle(entry.processor, entry.response, entry.callback));
    }

    private class QualityOfServiceRequest extends Request.Wrapper
    {
        private QualityOfServiceRequest(Request wrapped)
        {
            super(wrapped);
        }

        @Override
        public void accept(Processor processor) throws Exception
        {
            getWrapped().accept((rq, rs, cb) -> handle(processor, rs, cb));
        }

        private void handle(Processor processor, Response response, Callback callback)
        {
            while (true)
            {
                boolean process;
                try (AutoLock ignored = lock.lock())
                {
                    process = permits > 0;
                    if (process)
                        --permits;
                }

                if (process)
                {
                    process(processor, this, response, Callback.from(callback, QualityOfServiceHandler.this::release));
                    return;
                }

                try (AutoLock ignored = lock.lock())
                {
                    // Some permit was released, try again.
                    if (permits > 0)
                        continue;

                    queue.offer(new Entry(processor, this, response, callback));
                    return;
                }
            }
        }

        private void process(Processor processor, Request request, Response response, Callback callback)
        {
            try
            {
                processor.process(request, response, callback);
            }
            catch (Exception x)
            {
                // TODO: better exception handling?
                callback.failed(x);
            }
        }
    }

    private record Entry(Processor processor, QualityOfServiceRequest request, Response response, Callback callback)
    {
    }
}
