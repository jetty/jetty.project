//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.client.util;

import java.io.EOFException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.AutoLock;

public abstract class AbstractRequestContent implements Request.Content
{
    private static final Logger LOG = Log.getLogger(AbstractRequestContent.class);

    private final AutoLock lock = new AutoLock();
    private final String contentType;
    private Subscription subscription;
    private Throwable failure;

    protected AbstractRequestContent(String contentType)
    {
        this.contentType = contentType;
    }

    @Override
    public String getContentType()
    {
        return contentType;
    }

    @Override
    public Subscription subscribe(Consumer consumer, boolean emitInitialContent)
    {
        Subscription oldSubscription;
        Subscription newSubscription;
        try (AutoLock ignored = lock.lock())
        {
            if (subscription != null && !isReproducible())
                throw new IllegalStateException("Multiple subscriptions not supported on " + this);
            oldSubscription = subscription;
            newSubscription = subscription = newSubscription(consumer, emitInitialContent, failure);
        }
        if (oldSubscription != null)
            oldSubscription.fail(new EOFException("Content replay"));
        if (LOG.isDebugEnabled())
            LOG.debug("Content subscription for {}: {}", this, consumer);
        return newSubscription;
    }

    protected abstract Subscription newSubscription(Consumer consumer, boolean emitInitialContent, Throwable failure);

    @Override
    public void fail(Throwable failure)
    {
        Subscription subscription = null;
        try (AutoLock ignored = lock.lock())
        {
            if (this.failure == null)
            {
                this.failure = failure;
                subscription = this.subscription;
            }
        }
        if (subscription != null)
            subscription.fail(failure);
    }

    public abstract class AbstractSubscription implements Subscription
    {
        private final Consumer consumer;
        private final boolean emitInitialContent;
        private Throwable failure;
        private int demand;
        private boolean stalled;
        private boolean committed;

        public AbstractSubscription(Consumer consumer, boolean emitInitialContent, Throwable failure)
        {
            this.consumer = consumer;
            this.emitInitialContent = emitInitialContent;
            this.failure = failure;
            this.stalled = true;
        }

        @Override
        public void demand()
        {
            boolean produce;
            try (AutoLock ignored = lock.lock())
            {
                ++demand;
                produce = stalled;
                if (stalled)
                    stalled = false;
            }
            if (LOG.isDebugEnabled())
                LOG.debug("Content demand, producing {} for {}", produce, this);
            if (produce)
                produce();
        }

        private void produce()
        {
            while (true)
            {
                Throwable failure;
                boolean committed;
                try (AutoLock ignored = lock.lock())
                {
                    failure = this.failure;
                    committed = this.committed;
                }
                if (failure != null)
                {
                    notifyFailure(failure);
                    return;
                }

                if (committed || emitInitialContent)
                {
                    try
                    {
                        if (!produceContent(this::processContent))
                            return;
                    }
                    catch (Throwable x)
                    {
                        // Fail and loop around to notify the failure.
                        fail(x);
                    }
                }
                else
                {
                    if (!processContent(BufferUtil.EMPTY_BUFFER, false, Callback.NOOP))
                        return;
                }
            }
        }

        protected abstract boolean produceContent(Producer producer) throws Exception;

        @Override
        public void fail(Throwable failure)
        {
            try (AutoLock ignored = lock.lock())
            {
                if (this.failure == null)
                    this.failure = failure;
            }
        }

        private boolean processContent(ByteBuffer content, boolean last, Callback callback)
        {
            try (AutoLock ignored = lock.lock())
            {
                committed = true;
                --demand;
            }

            if (content != null)
                notifyContent(content, last, callback);
            else
                callback.succeeded();

            boolean noDemand;
            try (AutoLock ignored = lock.lock())
            {
                noDemand = demand == 0;
                if (noDemand)
                    stalled = true;
            }
            if (noDemand)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("No demand, processing stalled for {}", this);
                return false;
            }
            return true;
        }

        protected void notifyContent(ByteBuffer buffer, boolean last, Callback callback)
        {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Notifying content last={} {} for {}", last, BufferUtil.toDetailString(buffer), this);
                consumer.onContent(buffer, last, callback);
            }
            catch (Throwable x)
            {
                callback.failed(x);
                fail(x);
            }
        }

        private void notifyFailure(Throwable failure)
        {
            try
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Notifying failure for {}", this, failure);
                consumer.onFailure(failure);
            }
            catch (Exception x)
            {
                LOG.ignore(x);
            }
        }

        @Override
        public String toString()
        {
            int demand;
            boolean stalled;
            try (AutoLock ignored = lock.lock())
            {
                demand = this.demand;
                stalled = this.stalled;
            }
            return String.format("%s.%s@%x[demand=%d,stalled=%b]",
                getClass().getEnclosingClass().getSimpleName(),
                getClass().getSimpleName(), hashCode(), demand, stalled);
        }
    }

    public interface Producer
    {
        boolean produce(ByteBuffer content, boolean lastContent, Callback callback);
    }
}
