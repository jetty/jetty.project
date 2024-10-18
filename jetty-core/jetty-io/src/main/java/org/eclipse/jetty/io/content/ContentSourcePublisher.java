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

package org.eclipse.jetty.io.content;

import java.util.Objects;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.MathUtils;
import org.eclipse.jetty.util.StaticException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Wraps a {@link Content.Source} as a {@link Flow.Publisher}.
 * When content is requested via {@link Flow.Subscription#request(long)}, it is
 * read from the passed {@link Content.Source} and passed to {@link Flow.Subscriber#onNext(Object)}.
 * If no content is available, then the {@link Content.Source#demand(Runnable)} method is used to
 * ultimately call {@link Flow.Subscriber#onNext(Object)} once content is available.</p>
 * <p>{@link Content.Source} can be consumed only once and does not support multicast subscription.
 * {@link Content.Source} will be consumed fully, otherwise will be failed in case of any errors
 * to prevent resource leaks.</p>
 */
public class ContentSourcePublisher implements Flow.Publisher<Content.Chunk>
{
    private static final Logger LOG = LoggerFactory.getLogger(ContentSourcePublisher.class);

    private final AtomicReference<Content.Source> content;

    public ContentSourcePublisher(Content.Source content)
    {
        Objects.requireNonNull(content, "Content.Source must not be null");
        this.content = new AtomicReference<>(content);
    }

    @Override
    public void subscribe(Flow.Subscriber<? super Content.Chunk> subscriber)
    {
        // As per rule 1.11, we have decided to support SINGLE subscriber
        // in a UNICAST configuration for this implementation. It means
        // that Content.Source can be consumed only once.
        Content.Source content = this.content.getAndSet(null);
        if (content != null)
            onSubscribe(subscriber, content);
        else
            onMultiSubscribe(subscriber);
    }

    private void onSubscribe(Flow.Subscriber<? super Content.Chunk> subscriber, Content.Source content)
    {
        // As per rule 1.9, we need to throw a `java.lang.NullPointerException`
        // if the `Subscriber` is `null`
        if (subscriber == null)
        {
            NullPointerException error = new NullPointerException("Flow.Subscriber must not be null");
            content.fail(error);
            throw error;
        }

        ActiveSubscription subscription = new ActiveSubscription(content, subscriber);
        // As per rule 1.9, this method must return normally (i.e. not throw).
        try
        {
            subscriber.onSubscribe(subscription);
        }
        catch (Throwable err)
        {
            // As per rule 2.13, we MUST consider subscription cancelled and
            // MUST raise this error condition in a fashion that is adequate for the runtime environment.
            subscription.cancel(new SuppressedException(err));
            if (LOG.isTraceEnabled())
                LOG.trace("Flow.Subscriber " + subscriber + " violated rule 2.13", err);
        }
    }

    private void onMultiSubscribe(Flow.Subscriber<? super Content.Chunk> subscriber)
    {
        // As per rule 1.9, we need to throw a `java.lang.NullPointerException`
        // if the `Subscriber` is `null`
        if (subscriber == null)
            throw new NullPointerException("Flow.Subscriber must not be null");

        ExhaustedSubscription subscription = new ExhaustedSubscription();
        // As per 1.9, this method must return normally (i.e. not throw).
        try
        {
            // As per rule 1.9, the only legal way to signal about Subscriber rejection
            // is by calling onError (after calling onSubscribe).
            subscriber.onSubscribe(subscription);
            subscriber.onError(new IllegalStateException("Content.Source was exhausted."));
        }
        catch (Throwable err)
        {
            // As per rule 2.13, we MUST consider subscription cancelled and
            // MUST raise this error condition in a fashion that is adequate for the runtime environment.
            if (LOG.isTraceEnabled())
                LOG.trace("Flow.Subscriber " + subscriber + " violated rule 2.13", err);
        }
    }

    private static final class ExhaustedSubscription implements Flow.Subscription
    {
        @Override
        public void request(long n)
        {
            // As per rules 3.6 and 3.7, after the Subscription is cancelled all operations MUST be NOPs.
        }

        @Override
        public void cancel()
        {
            // As per rules 3.6 and 3.7, after the Subscription is cancelled all operations MUST be NOPs.
        }
    }

    private static final class ActiveSubscription extends IteratingCallback implements Flow.Subscription, Runnable
    {
        private static final long NO_MORE_DEMAND = -1;
        private static final Throwable COMPLETED = new StaticException("Source.Content read fully");
        private final AtomicReference<Throwable> cancelled;
        private final AtomicLong demand;
        private Content.Source content;
        private Flow.Subscriber<? super Content.Chunk> subscriber;

        public ActiveSubscription(Content.Source content, Flow.Subscriber<? super Content.Chunk> subscriber)
        {
            this.cancelled = new AtomicReference<>(null);
            this.demand = new AtomicLong(0);
            this.content = content;
            this.subscriber = subscriber;
        }

        // As per rule 3.3, Subscription MUST place an upper bound on possible synchronous
        // recursion between Publisher and Subscriber
        //
        // As per rule 1.3, onSubscribe, onNext, onError and onComplete signaled to a
        // Subscriber MUST be signaled serially.
        //
        // IteratingCallback guarantee that process() method will be executed by one thread only.
        // The process() method can be only initiated from request() or cancel() demands methods.
        @Override
        protected Action process()
        {
            Throwable cancelled = this.cancelled.get();
            if (cancelled != null)
            {
                // As per rule 3.13, Subscription.cancel() MUST request the Publisher to eventually
                // drop any references to the corresponding subscriber.
                this.demand.set(NO_MORE_DEMAND);
                if (cancelled != COMPLETED)
                    this.content.fail(cancelled);
                this.content = null;
                try
                {
                    if (cancelled == COMPLETED)
                        this.subscriber.onComplete();
                    else if (!(cancelled instanceof SuppressedException))
                        this.subscriber.onError(cancelled);
                }
                catch (Throwable err)
                {
                    if (LOG.isTraceEnabled())
                        LOG.trace("Flow.Subscriber " + subscriber + " violated rule 2.13", err);
                }
                this.subscriber = null;
                return Action.SUCCEEDED;
            }

            Content.Chunk chunk = content.read();

            if (chunk == null)
            {
                // Pass this, which is Invocable
                content.demand(this);
                return Action.SCHEDULED;
            }

            if (Content.Chunk.isFailure(chunk))
            {
                cancel(chunk.getFailure());
                chunk.release();
                return Action.IDLE;
            }

            try
            {
                this.subscriber.onNext(chunk);
            }
            catch (Throwable err)
            {
                cancel(new SuppressedException(err));
                if (LOG.isTraceEnabled())
                    LOG.trace("Flow.Subscriber " + subscriber + " violated rule 2.13", err);
            }
            chunk.release();

            if (chunk.isLast())
            {
                cancel(COMPLETED);
                return Action.IDLE;
            }

            if (demand.decrementAndGet() > 0)
                this.iterate();

            return Action.IDLE;
        }

        /**
         * Called by {@link Content.Source#demand(Runnable)}
         */
        @Override
        public void run()
        {
            succeeded();
        }

        @Override
        public void request(long n)
        {
            // As per rules 3.6 and 3.7, after the Subscription is cancelled all operations MUST be NOPs.
            if (cancelled.get() != null)
                return;

            // As per rule 3.9, MUST signal onError with a java.lang.IllegalArgumentException if the argument is <= 0.
            if (n <= 0L)
            {
                String errorMsg = "Flow.Subscriber " + subscriber + " violated rule 3.9: non-positive requests are not allowed.";
                cancel(new IllegalArgumentException(errorMsg));
                return;
            }

            // As per rule 3.17, when demand overflows `Long.MAX_VALUE`
            // we treat the signalled demand as "effectively unbounded"
            if (demand.updateAndGet(it -> it == NO_MORE_DEMAND ? it : MathUtils.cappedAdd(it, n)) != NO_MORE_DEMAND)
                this.iterate();
        }

        @Override
        public void cancel()
        {
            cancel(new CancelledException());
        }

        public void cancel(Throwable cause)
        {
            // As per rules 3.6 and 3.7, after the Subscription is cancelled all operations MUST be NOPs.
            //
            // As per rule 3.5, this handles cancellation requests, and is idempotent, thread-safe and not
            // synchronously performing heavy computations
            if (cancelled.compareAndSet(null, cause))
                this.iterate();
        }

        // Publisher notes
        //
        // 1.6 If a Publisher signals either onError or onComplete on a Subscriber,
        // that Subscriber’s Subscription MUST be considered cancelled.
        // 2.4 Subscriber.onComplete() and Subscriber.onError(Throwable t) MUST consider the
        // Subscription cancelled after having received the signal.
        //
        // Publisher failed -> cancel(Throwable)
        // 1.4 If a Publisher fails it MUST signal an onError.
        //
        // Publisher succeeded -> cancel(COMPLETED)
        // 1.5 If a Publisher terminates successfully (finite stream) it MUST signal an onComplete.

        // Subscriber
        // 2.13 In the case that this rule is violated, any associated Subscription to the Subscriber
        // MUST be considered as cancelled, and the caller MUST raise this error condition in a
        // fashion that is adequate for the runtime environment.
        //
        // Subscriber.onSubscribe/onNext/onError/onComplete failed -> cancel(new Suppressed(cause))

        // Subscription notes
        //
        // Subscription.cancel -> cancel(new Cancelled())
        // It's not clearly specified in the specification, but according to:
        // - the issue: https://github.com/reactive-streams/reactive-streams-jvm/issues/458
        // - TCK test 'untested_spec108_possiblyCanceledSubscriptionShouldNotReceiveOnErrorOrOnCompleteSignals'
        // - 1.8 If a Subscription is cancelled its Subscriber MUST eventually stop being signaled.
        //
        // Subscription.request with negative argument -> cancel(err)
        // 3.9 While the Subscription is not cancelled, Subscription.request(long n) MUST signal onError with a
        // java.lang.IllegalArgumentException if the argument is <= 0.
    }

    private static class SuppressedException extends Exception
    {
        SuppressedException(String message)
        {
            super(message);
        }

        SuppressedException(Throwable cause)
        {
            super(cause.getMessage(), cause);
        }
    }

    private static class CancelledException extends SuppressedException
    {
        CancelledException()
        {
            super("Subscription was cancelled");
        }
    }
}
