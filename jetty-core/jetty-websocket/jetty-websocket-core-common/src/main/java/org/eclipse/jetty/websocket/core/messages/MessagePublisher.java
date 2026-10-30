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

package org.eclipse.jetty.websocket.core.messages;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Flow;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.MathUtils;
import org.eclipse.jetty.util.thread.AutoLock;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link MessageSink} that publishes the payloads of the frames of a
 * single WebSocket message to a {@link Flow.Subscriber}.</p>
 * <p>Frames are published in order, one {@link ByteBuffer} per frame with a
 * payload, without copying: the {@code ByteBuffer} passed to
 * {@link Flow.Subscriber#onNext(Object)} is valid only for the duration of
 * that call, and must be consumed or copied before returning from it.</p>
 * <p>The demand for WebSocket frames follows the demand of the subscriber:
 * the next frame of the message is demanded from the {@link CoreSession}
 * only when the subscriber has requested more items, so that a slow
 * subscriber applies backpressure to the remote peer.
 * {@link Flow.Subscriber#onComplete()} is signalled after the payload of the
 * last frame of the message, and {@link Flow.Subscriber#onError(Throwable)}
 * if the message fails.
 * Cancelling the {@link Flow.Subscription} discards the remaining frames of
 * the message, which are demanded and consumed without being published.</p>
 * <p>This publisher supports a single subscriber, and may be subscribed
 * before or after the frames have been accepted; any further subscriber is
 * signalled an {@link IllegalStateException}.</p>
 * <p>The demand for the next message is not performed by this class, but by
 * the caller when the callback associated with the last frame of the message
 * is completed.</p>
 */
public class MessagePublisher implements Flow.Publisher<ByteBuffer>, MessageSink
{
    private static final Logger LOG = LoggerFactory.getLogger(MessagePublisher.class);

    private final AutoLock lock = new AutoLock();
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();
    private final CoreSession session;
    private Flow.Subscriber<? super ByteBuffer> subscriber;
    private boolean subscribed;
    private long demand;
    private boolean publishing;
    private boolean started;
    private boolean frameDemanded;
    private boolean finReceived;
    private boolean cancelled;
    private boolean terminated;
    private Throwable failure;

    public MessagePublisher(CoreSession session)
    {
        this.session = Objects.requireNonNull(session, "CoreSession");
    }

    @Override
    public void accept(Frame frame, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("accepting {}", frame);

        Runnable action;
        try (AutoLock ignored = lock.lock())
        {
            started = true;
            frameDemanded = false;
            if (frame.isFin())
                finReceived = true;

            if (failure != null)
            {
                Throwable cause = failure;
                action = () -> callback.failed(cause);
            }
            else if (cancelled || (!frame.isFin() && !frame.hasPayload()))
            {
                // Nothing to publish: consume the frame, and demand
                // the next frame of the message if there is one.
                boolean demandNext = !frame.isFin();
                if (demandNext)
                    frameDemanded = true;
                action = () ->
                {
                    callback.succeeded();
                    if (demandNext)
                        session.demand();
                };
            }
            else
            {
                entries.offer(new Entry(frame, callback));
                action = this::publish;
            }
        }
        action.run();
    }

    @Override
    public void fail(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("fail()", failure);

        if (terminate(failure))
            publish();
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber)
    {
        Objects.requireNonNull(subscriber, "subscriber");

        boolean accepted;
        try (AutoLock ignored = lock.lock())
        {
            accepted = this.subscriber == null;
            if (accepted)
                this.subscriber = subscriber;
        }

        if (!accepted)
        {
            subscriber.onSubscribe(new Flow.Subscription()
            {
                @Override
                public void request(long n)
                {
                }

                @Override
                public void cancel()
                {
                }
            });
            subscriber.onError(new IllegalStateException("Already subscribed"));
            return;
        }

        try
        {
            subscriber.onSubscribe(new Subscription());
        }
        catch (Throwable x)
        {
            onSubscriberFailure(x);
            return;
        }

        try (AutoLock ignored = lock.lock())
        {
            subscribed = true;
        }
        publish();
    }

    private void request(long n)
    {
        if (n <= 0)
        {
            if (terminate(new IllegalArgumentException("Non-positive request: " + n)))
                publish();
            return;
        }

        try (AutoLock ignored = lock.lock())
        {
            if (cancelled || terminated)
                return;
            demand = MathUtils.cappedAdd(demand, n);
        }
        publish();
    }

    private void cancel()
    {
        List<Entry> drained;
        boolean demandNext;
        try (AutoLock ignored = lock.lock())
        {
            if (cancelled || failure != null)
                return;
            cancelled = true;
            demand = 0;
            // If a signal is being delivered, the
            // publishing loop discards the frames.
            if (publishing)
                return;
            drained = drain();
            demandNext = demandNextFrame();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("cancelled {}", this);

        discard(drained, demandNext);
    }

    /**
     * <p>Fails the frames not yet published, and records the failure
     * so that frames accepted later are failed too.</p>
     *
     * @param cause the failure
     * @return whether the failure was recorded, false if the publisher had already failed
     */
    private boolean terminate(Throwable cause)
    {
        List<Entry> drained;
        try (AutoLock ignored = lock.lock())
        {
            if (failure != null)
                return false;
            failure = cause;
            demand = 0;
            drained = drain();
        }
        drained.forEach(entry -> entry.callback.failed(cause));
        return true;
    }

    private void onSubscriberFailure(Throwable failure)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("subscriber failure {}", this, failure);

        // The subscriber must not throw; if it does, it is considered
        // cancelled and the failure is propagated to the WebSocket session.
        try (AutoLock ignored = lock.lock())
        {
            terminated = true;
            publishing = false;
        }
        terminate(failure);
    }

    /**
     * <p>Publishes the queued frames to the subscriber, while the subscriber has demand.</p>
     * <p>Signals are delivered serially by a single thread at a time, outside the lock;
     * other threads that call this method while a signal is being delivered return
     * immediately, and the delivering thread picks up their changes of state.</p>
     */
    private void publish()
    {
        try (AutoLock ignored = lock.lock())
        {
            if (publishing)
                return;
            publishing = true;
        }

        while (true)
        {
            Flow.Subscriber<? super ByteBuffer> subscriber;
            Entry entry = null;
            Throwable error = null;
            List<Entry> drained = null;
            boolean demandNext = false;
            try (AutoLock ignored = lock.lock())
            {
                subscriber = this.subscriber;
                if (cancelled)
                {
                    drained = drain();
                    demandNext = demandNextFrame();
                    publishing = false;
                }
                else if (!subscribed || terminated)
                {
                    publishing = false;
                    return;
                }
                else if (failure != null)
                {
                    error = failure;
                    terminated = true;
                    publishing = false;
                }
                else
                {
                    entry = entries.peek();
                    if (entry == null)
                    {
                        // Frames are demanded only when the subscriber wants more.
                        demandNext = demand > 0 && demandNextFrame();
                        publishing = false;
                    }
                    else if (entry.frame.hasPayload())
                    {
                        if (demand == 0)
                        {
                            publishing = false;
                            return;
                        }
                        if (demand != Long.MAX_VALUE)
                            --demand;
                    }
                    if (entry != null)
                    {
                        entries.poll();
                        if (entry.frame.isFin())
                            terminated = true;
                    }
                }
            }

            if (drained != null)
            {
                discard(drained, demandNext);
                return;
            }

            if (error != null)
            {
                signalError(subscriber, error);
                return;
            }

            if (entry == null)
            {
                if (demandNext)
                    session.demand();
                return;
            }

            if (!signal(subscriber, entry))
                return;
        }
    }

    /**
     * @param subscriber the subscriber to signal
     * @param entry the entry to publish
     * @return whether publishing may continue with the next entry
     */
    private boolean signal(Flow.Subscriber<? super ByteBuffer> subscriber, Entry entry)
    {
        Frame frame = entry.frame;
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("publishing {}", frame);
            if (frame.hasPayload())
                subscriber.onNext(frame.getPayload());
            if (frame.isFin())
                subscriber.onComplete();
        }
        catch (Throwable x)
        {
            onSubscriberFailure(x);
            entry.callback.failed(x);
            return false;
        }

        // Completing the callback of the last frame
        // triggers the demand for the next message.
        entry.callback.succeeded();
        if (!frame.isFin())
            return true;

        try (AutoLock ignored = lock.lock())
        {
            publishing = false;
        }
        return false;
    }

    private void signalError(Flow.Subscriber<? super ByteBuffer> subscriber, Throwable error)
    {
        try
        {
            if (LOG.isDebugEnabled())
                LOG.debug("publishing error {}", this, error);
            subscriber.onError(error);
        }
        catch (Throwable x)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("subscriber failure {}", this, x);
        }
    }

    private void discard(List<Entry> entries, boolean demandNext)
    {
        entries.forEach(entry -> entry.callback.succeeded());
        if (demandNext)
            session.demand();
    }

    private List<Entry> drain()
    {
        assert lock.isHeldByCurrentThread();
        List<Entry> drained = new ArrayList<>(entries);
        entries.clear();
        return drained;
    }

    /**
     * @return whether the next frame of the message must be demanded,
     * which is the case only if a frame has been received, the last
     * frame has not been received, and the next frame has not already
     * been demanded
     */
    private boolean demandNextFrame()
    {
        assert lock.isHeldByCurrentThread();
        if (!started || finReceived || frameDemanded)
            return false;
        frameDemanded = true;
        return true;
    }

    @Override
    public String toString()
    {
        try (AutoLock ignored = lock.lock())
        {
            return String.format("%s@%x[queued=%d,demand=%d,subscribed=%b,cancelled=%b,terminated=%b]",
                getClass().getSimpleName(), hashCode(), entries.size(), demand, subscribed, cancelled, terminated);
        }
    }

    private class Subscription implements Flow.Subscription
    {
        @Override
        public void request(long n)
        {
            MessagePublisher.this.request(n);
        }

        @Override
        public void cancel()
        {
            MessagePublisher.this.cancel();
        }
    }

    private record Entry(Frame frame, Callback callback)
    {
    }
}
