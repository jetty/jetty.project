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

package org.eclipse.jetty.io.content;

import java.util.concurrent.Flow;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.MathUtils;
import org.eclipse.jetty.util.thread.AutoLock;

public class ContentSourcePublisher implements Flow.Publisher<Content.Chunk>
{
    private final Content.Source content;

    public ContentSourcePublisher(Content.Source content)
    {
        this.content = content;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super Content.Chunk> subscriber)
    {
        subscriber.onSubscribe(new SubscriptionImpl(content, subscriber));
    }

    private static class SubscriptionImpl implements Flow.Subscription
    {
        private final AutoLock lock = new AutoLock();
        private final Content.Source content;
        private final Flow.Subscriber<? super Content.Chunk> subscriber;
        private long demand;
        private boolean stalled;
        private boolean cancelled;
        private boolean terminated;

        public SubscriptionImpl(Content.Source content, Flow.Subscriber<? super Content.Chunk> subscriber)
        {
            this.content = content;
            this.subscriber = subscriber;
            this.stalled = true;
        }

        @Override
        public void request(long n)
        {
            boolean process = false;
            Throwable failure = null;
            try (AutoLock ignored = lock.lock())
            {
                if (cancelled || terminated)
                    return;
                if (n <= 0)
                {
                    terminated = true;
                    failure = new IllegalArgumentException("invalid demand " + n);
                }
                demand = MathUtils.cappedAdd(demand, n);
                if (stalled)
                {
                    stalled = false;
                    process = true;
                }
            }
            if (failure != null)
                subscriber.onError(failure);
            else if (process)
                process();
        }

        @Override
        public void cancel()
        {
            try (AutoLock ignored = lock.lock())
            {
                cancelled = true;
            }
        }

        private void process()
        {
            while (true)
            {
                try (AutoLock ignored = lock.lock())
                {
                    if (demand > 0)
                    {
                        --demand;
                    }
                    else
                    {
                        stalled = true;
                        return;
                    }
                }

                Content.Chunk chunk = content.read();

                if (chunk == null)
                {
                    try (AutoLock ignored = lock.lock())
                    {
                        // Restore the demand decremented above.
                        ++demand;
                        stalled = true;
                    }
                    content.demand(this::process);
                    return;
                }

                if (chunk instanceof Content.Chunk.Error error)
                {
                    terminate();
                    subscriber.onError(error.getCause());
                    return;
                }

                subscriber.onNext(chunk);

                if (chunk.isLast())
                {
                    terminate();
                    subscriber.onComplete();
                    return;
                }
            }
        }

        private void terminate()
        {
            try (AutoLock ignored = lock.lock())
            {
                terminated = true;
            }
        }
    }
}
