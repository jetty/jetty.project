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

package org.eclipse.jetty.websocket.common;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.messages.MessagePublisher;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MessagePublisherTest
{
    private final DemandCountingSession session = new DemandCountingSession();
    private final MessagePublisher publisher = new MessagePublisher(session);

    @Test
    public void testSingleFrameMessage()
    {
        TestCallback callback = new TestCallback();
        publisher.accept(frame("hello", true), callback);

        TestSubscriber subscriber = new TestSubscriber(Long.MAX_VALUE);
        publisher.subscribe(subscriber);

        assertThat(subscriber.items, contains("hello"));
        assertTrue(subscriber.completed);
        assertTrue(callback.succeeded);
        // The demand for the next message is performed by the caller.
        assertThat(session.demands, is(0));
    }

    @Test
    public void testFramesAreDemandedAsRequested()
    {
        TestCallback callback1 = new TestCallback();
        publisher.accept(frame("hello", false), callback1);

        TestSubscriber subscriber = new TestSubscriber(0);
        publisher.subscribe(subscriber);
        assertThat(subscriber.items, empty());
        assertFalse(callback1.succeeded);
        assertThat(session.demands, is(0));

        subscriber.request(1);
        assertThat(subscriber.items, contains("hello"));
        assertTrue(callback1.succeeded);
        // The next frame is not demanded until the subscriber requests more.
        assertThat(session.demands, is(0));

        subscriber.request(1);
        assertThat(session.demands, is(1));

        TestCallback callback2 = new TestCallback();
        publisher.accept(frame(" world", true), callback2);
        assertThat(subscriber.items, contains("hello", " world"));
        assertTrue(subscriber.completed);
        assertTrue(callback2.succeeded);
        assertThat(session.demands, is(1));
    }

    @Test
    public void testRequestCoveringSeveralFramesDemandsTheNextFrame()
    {
        TestCallback callback1 = new TestCallback();
        publisher.accept(frame("hello", false), callback1);

        TestSubscriber subscriber = new TestSubscriber(2);
        publisher.subscribe(subscriber);
        assertThat(subscriber.items, contains("hello"));
        assertTrue(callback1.succeeded);
        assertThat(session.demands, is(1));

        TestCallback callback2 = new TestCallback();
        publisher.accept(frame(" world", true), callback2);
        assertThat(subscriber.items, contains("hello", " world"));
        assertTrue(subscriber.completed);
        assertTrue(callback2.succeeded);
        assertThat(session.demands, is(1));
    }

    @Test
    public void testUnboundedRequestDemandsEveryFrame()
    {
        TestSubscriber subscriber = new TestSubscriber(Long.MAX_VALUE);
        publisher.subscribe(subscriber);
        assertThat(session.demands, is(0));

        TestCallback callback1 = new TestCallback();
        publisher.accept(frame("hello", false), callback1);
        assertThat(subscriber.items, contains("hello"));
        assertTrue(callback1.succeeded);
        assertThat(session.demands, is(1));

        TestCallback callback2 = new TestCallback();
        publisher.accept(frame(" world", false), callback2);
        assertThat(subscriber.items, contains("hello", " world"));
        assertTrue(callback2.succeeded);
        assertThat(session.demands, is(2));

        TestCallback callback3 = new TestCallback();
        publisher.accept(emptyFrame(true), callback3);
        assertThat(subscriber.items, contains("hello", " world"));
        assertTrue(subscriber.completed);
        assertTrue(callback3.succeeded);
        assertThat(session.demands, is(2));
    }

    @Test
    public void testRequestFromOnNext()
    {
        TestSubscriber subscriber = new TestSubscriber(1);
        subscriber.onNextAction = () -> subscriber.request(1);
        publisher.subscribe(subscriber);

        publisher.accept(frame("hello", false), new TestCallback());
        assertThat(subscriber.items, contains("hello"));
        assertThat(session.demands, is(1));

        publisher.accept(frame(" world", true), new TestCallback());
        assertThat(subscriber.items, contains("hello", " world"));
        assertTrue(subscriber.completed);
        assertThat(session.demands, is(1));
    }

    @Test
    public void testEmptyNonFinFrameIsConsumedWithoutPublishing()
    {
        TestSubscriber subscriber = new TestSubscriber(0);
        publisher.subscribe(subscriber);

        TestCallback callback = new TestCallback();
        publisher.accept(emptyFrame(false), callback);

        assertThat(subscriber.items, empty());
        assertTrue(callback.succeeded);
        assertThat(session.demands, is(1));
    }

    @Test
    public void testEmptyMessageCompletesWithoutRequest()
    {
        TestCallback callback = new TestCallback();
        publisher.accept(emptyFrame(true), callback);

        TestSubscriber subscriber = new TestSubscriber(0);
        publisher.subscribe(subscriber);

        assertThat(subscriber.items, empty());
        assertTrue(subscriber.completed);
        assertTrue(callback.succeeded);
        assertThat(session.demands, is(0));
    }

    @Test
    public void testCancelDiscardsRemainingFrames()
    {
        TestSubscriber subscriber = new TestSubscriber(Long.MAX_VALUE);
        subscriber.onNextAction = subscriber::cancel;
        publisher.subscribe(subscriber);

        TestCallback callback1 = new TestCallback();
        publisher.accept(frame("hello", false), callback1);
        assertThat(subscriber.items, contains("hello"));
        assertTrue(callback1.succeeded);
        // The rest of the message is demanded to be discarded.
        assertThat(session.demands, is(1));

        TestCallback callback2 = new TestCallback();
        publisher.accept(frame(" world", false), callback2);
        assertThat(subscriber.items, contains("hello"));
        assertTrue(callback2.succeeded);
        assertThat(session.demands, is(2));

        TestCallback callback3 = new TestCallback();
        publisher.accept(frame("!", true), callback3);
        assertThat(subscriber.items, contains("hello"));
        assertTrue(callback3.succeeded);
        assertFalse(subscriber.completed);
        assertThat(subscriber.error, nullValue());
        assertThat(session.demands, is(2));
    }

    @Test
    public void testCancelWithoutDemandDiscardsQueuedFrame()
    {
        TestSubscriber subscriber = new TestSubscriber(0);
        publisher.subscribe(subscriber);

        TestCallback callback1 = new TestCallback();
        publisher.accept(frame("hello", false), callback1);
        assertFalse(callback1.succeeded);
        assertThat(session.demands, is(0));

        subscriber.cancel();
        assertTrue(callback1.succeeded);
        assertThat(session.demands, is(1));

        TestCallback callback2 = new TestCallback();
        publisher.accept(frame(" world", true), callback2);
        assertTrue(callback2.succeeded);
        assertThat(subscriber.items, empty());
        assertThat(session.demands, is(1));
    }

    @Test
    public void testFailureFailsFramesAndIsSignalledToLateSubscriber()
    {
        TestCallback callback1 = new TestCallback();
        publisher.accept(frame("hello", false), callback1);

        Throwable failure = new IllegalStateException("failure");
        publisher.fail(failure);
        assertThat(callback1.failure, sameInstance(failure));

        TestSubscriber subscriber = new TestSubscriber(Long.MAX_VALUE);
        publisher.subscribe(subscriber);
        assertThat(subscriber.items, empty());
        assertThat(subscriber.error, sameInstance(failure));

        TestCallback callback2 = new TestCallback();
        publisher.accept(frame(" world", true), callback2);
        assertThat(callback2.failure, sameInstance(failure));
        assertThat(session.demands, is(0));
    }

    @Test
    public void testFailureAfterSubscription()
    {
        TestSubscriber subscriber = new TestSubscriber(Long.MAX_VALUE);
        publisher.subscribe(subscriber);
        publisher.accept(frame("hello", false), new TestCallback());
        assertThat(subscriber.items, contains("hello"));

        Throwable failure = new IllegalStateException("failure");
        publisher.fail(failure);
        assertThat(subscriber.error, sameInstance(failure));
        assertFalse(subscriber.completed);

        TestCallback callback = new TestCallback();
        publisher.accept(frame(" world", true), callback);
        assertThat(callback.failure, sameInstance(failure));
        assertThat(subscriber.items, contains("hello"));
    }

    @Test
    public void testSubscriberFailureFailsTheMessage()
    {
        TestSubscriber subscriber = new TestSubscriber(Long.MAX_VALUE);
        subscriber.onNextAction = () ->
        {
            throw new IllegalStateException("subscriber failure");
        };
        publisher.subscribe(subscriber);

        TestCallback callback1 = new TestCallback();
        publisher.accept(frame("hello", false), callback1);
        assertThat(callback1.failure, instanceOf(IllegalStateException.class));
        // A subscriber that throws is not signalled anymore.
        assertThat(subscriber.error, nullValue());
        assertThat(session.demands, is(0));

        TestCallback callback2 = new TestCallback();
        publisher.accept(frame(" world", true), callback2);
        assertThat(callback2.failure, instanceOf(IllegalStateException.class));
        assertThat(subscriber.items, contains("hello"));
        assertFalse(subscriber.completed);
    }

    @Test
    public void testSecondSubscriberIsRejected()
    {
        TestSubscriber subscriber1 = new TestSubscriber(Long.MAX_VALUE);
        publisher.subscribe(subscriber1);

        TestSubscriber subscriber2 = new TestSubscriber(Long.MAX_VALUE);
        publisher.subscribe(subscriber2);
        assertThat(subscriber2.error, instanceOf(IllegalStateException.class));

        publisher.accept(frame("hello", true), new TestCallback());
        assertThat(subscriber1.items, contains("hello"));
        assertTrue(subscriber1.completed);
        assertThat(subscriber2.items, empty());
    }

    @Test
    public void testNonPositiveRequestSignalsError()
    {
        TestSubscriber subscriber = new TestSubscriber(0);
        publisher.subscribe(subscriber);

        subscriber.request(0);
        assertThat(subscriber.error, instanceOf(IllegalArgumentException.class));

        TestCallback callback = new TestCallback();
        publisher.accept(frame("hello", true), callback);
        assertThat(callback.failure, instanceOf(IllegalArgumentException.class));
        assertThat(subscriber.items, empty());
    }

    private static Frame frame(String payload, boolean fin)
    {
        return new Frame(OpCode.BINARY).setPayload(payload).setFin(fin);
    }

    private static Frame emptyFrame(boolean fin)
    {
        return new Frame(OpCode.BINARY).setFin(fin);
    }

    private static class DemandCountingSession extends CoreSession.Empty
    {
        private int demands;

        @Override
        public void demand()
        {
            demands++;
        }
    }

    private static class TestCallback implements Callback
    {
        private boolean succeeded;
        private Throwable failure;

        @Override
        public void succeeded()
        {
            succeeded = true;
        }

        @Override
        public void failed(Throwable x)
        {
            failure = x;
        }
    }

    private static class TestSubscriber implements Flow.Subscriber<ByteBuffer>
    {
        private final List<String> items = new ArrayList<>();
        private final long initialRequest;
        private Runnable onNextAction = () ->
        {
        };
        private Flow.Subscription subscription;
        private boolean completed;
        private Throwable error;

        private TestSubscriber(long initialRequest)
        {
            this.initialRequest = initialRequest;
        }

        private void request(long n)
        {
            subscription.request(n);
        }

        private void cancel()
        {
            subscription.cancel();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription)
        {
            this.subscription = subscription;
            if (initialRequest > 0)
                subscription.request(initialRequest);
        }

        @Override
        public void onNext(ByteBuffer payload)
        {
            items.add(BufferUtil.toString(payload, StandardCharsets.UTF_8));
            onNextAction.run();
        }

        @Override
        public void onError(Throwable failure)
        {
            error = failure;
        }

        @Override
        public void onComplete()
        {
            completed = true;
        }
    }
}
