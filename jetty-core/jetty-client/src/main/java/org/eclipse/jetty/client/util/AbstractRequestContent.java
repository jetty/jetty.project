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

package org.eclipse.jetty.client.util;

import java.nio.ByteBuffer;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Partial implementation of {@link Request.Content}.</p>
 */
public abstract class AbstractRequestContent implements Request.Content
{
    private static final Logger LOG = LoggerFactory.getLogger(AbstractRequestContent.class);

    private final AutoLock lock = new AutoLock();
    private final String contentType;

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
        Subscription subscription = newSubscription(consumer, emitInitialContent);
        if (LOG.isDebugEnabled())
            LOG.debug("Content subscription for {}: {}", subscription, consumer);
        return subscription;
    }

    protected abstract Subscription newSubscription(Consumer consumer, boolean emitInitialContent);

    /**
     * <p>Partial implementation of {@code Subscription}.</p>
     * <p>Implements the algorithm described in {@link Request.Content}.</p>
     */
    public abstract class AbstractSubscription implements Subscription
    {
        private final Consumer consumer;
        private final boolean emitInitialContent;
        private Throwable failure;
        private int demand;
        // Whether content production was stalled because there was no demand.
        private boolean stalled;
        // Whether the first content has been produced.
        private boolean committed;

        public AbstractSubscription(Consumer consumer, boolean emitInitialContent)
        {
            this.consumer = consumer;
            this.emitInitialContent = emitInitialContent;
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

        /**
         * <p>Subclasses implement this method to produce content,
         * without worrying about demand or exception handling.</p>
         * <p>Typical implementation (pseudo code):</p>
         * <pre>
         * protected boolean produceContent(Producer producer) throws Exception
         * {
         *     // Step 1: try to produce content, exceptions may be thrown during production
         *     //  (for example, producing content reading from an InputStream may throw).
         *
         *     // Step 2A: content could be produced.
         *     ByteBuffer buffer = ...;
         *     boolean last = ...;
         *     Callback callback = ...;
         *     return producer.produce(buffer, last, callback);
         *
         *     // Step 2B: content could not be produced.
         *     //  (for example it is not available yet)
         *     return false;
         * }
         * </pre>
         *
         * @param producer the producer to notify when content can be produced
         * @return whether content production should continue
         * @throws Exception when content production fails
         */
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
                LOG.trace("Failure while notifying content failure {}", failure, x);
            }
        }

        @Override
        public String toString()
        {
            int demand;
            boolean stalled;
            boolean committed;
            try (AutoLock ignored = lock.lock())
            {
                demand = this.demand;
                stalled = this.stalled;
                committed = this.committed;
            }
            return String.format("%s.%s@%x[demand=%d,stalled=%b,committed=%b,emitInitial=%b]",
                getClass().getEnclosingClass().getSimpleName(),
                getClass().getSimpleName(), hashCode(), demand, stalled, committed, emitInitialContent);
        }
    }

    public interface Producer
    {
        boolean produce(ByteBuffer content, boolean lastContent, Callback callback);
    }
}
