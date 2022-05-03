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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class RequestContentBehaviorTest
{
    private static Path emptyFile;
    private static Path smallFile;

    @BeforeAll
    public static void prepare() throws IOException
    {
        Path testPath = MavenTestingUtils.getTargetTestingPath();
        Files.createDirectories(testPath);
        emptyFile = testPath.resolve("empty.txt");
        Files.write(emptyFile, new byte[0]);
        smallFile = testPath.resolve("small.txt");
        byte[] bytes = new byte[64];
        Arrays.fill(bytes, (byte)'#');
        Files.write(smallFile, bytes);
    }

    @AfterAll
    public static void dispose() throws IOException
    {
        if (smallFile != null)
            Files.delete(smallFile);
        if (emptyFile != null)
            Files.delete(emptyFile);
    }

    public static List<Request.Content> emptyContents() throws IOException
    {
        return List.of(
            new AsyncRequestContent()
            {
                {
                    close();
                }
            },
            new ByteBufferRequestContent(),
            new BytesRequestContent(),
            new FormRequestContent(new Fields()),
            new InputStreamRequestContent(IO.getClosedStream()),
            new MultiPartRequestContent()
            {
                {
                    close();
                }
            },
            new PathRequestContent(emptyFile),
            new StringRequestContent("")
        );
    }

    @ParameterizedTest
    @MethodSource("emptyContents")
    public void testEmptyContentEmitInitialFirstDemand(Request.Content content) throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        Request.Content.Subscription subscription = content.subscribe((buffer, last, callback) ->
        {
            if (last)
                latch.countDown();
        }, true);

        subscription.demand();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("emptyContents")
    public void testEmptyContentDontEmitInitialFirstDemand(Request.Content content) throws Exception
    {
        AtomicBoolean initial = new AtomicBoolean(true);
        AtomicReference<CountDownLatch> latch = new AtomicReference<>(new CountDownLatch(1));
        Request.Content.Subscription subscription = content.subscribe((buffer, last, callback) ->
        {
            if (initial.get())
            {
                if (!last)
                    latch.get().countDown();
            }
            else
            {
                if (last)
                    latch.get().countDown();
            }
        }, false);

        // Initial demand should have last=false.
        subscription.demand();

        assertTrue(latch.get().await(5, TimeUnit.SECONDS));

        // More demand should have last=true.
        initial.set(false);
        latch.set(new CountDownLatch(1));
        subscription.demand();

        assertTrue(latch.get().await(5, TimeUnit.SECONDS));
    }

    public static List<Request.Content> smallContents() throws IOException
    {
        return List.of(
            new AsyncRequestContent(ByteBuffer.allocate(64))
            {
                {
                    close();
                }
            },
            new ByteBufferRequestContent(ByteBuffer.allocate(64)),
            new BytesRequestContent(new byte[64]),
            new FormRequestContent(new Fields()
            {
                {
                    add("foo", "bar");
                }
            }),
            new InputStreamRequestContent(new ByteArrayInputStream(new byte[64])),
            new MultiPartRequestContent()
            {
                {
                    addFieldPart("field", new StringRequestContent("*".repeat(64)), null);
                    close();
                }
            },
            new PathRequestContent(smallFile),
            new StringRequestContent("x".repeat(64))
        );
    }

    @ParameterizedTest
    @MethodSource("smallContents")
    public void testSmallContentEmitInitialFirstDemand(Request.Content content) throws Exception
    {
        AtomicBoolean initial = new AtomicBoolean(true);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Request.Content.Subscription> subscriptionRef = new AtomicReference<>();
        Request.Content.Subscription subscription = content.subscribe((buffer, last, callback) ->
        {
            if (initial.getAndSet(false))
                assertTrue(buffer.hasRemaining());
            if (last)
                latch.countDown();
            else
                subscriptionRef.get().demand();
        }, true);
        subscriptionRef.set(subscription);

        // Initial demand.
        subscription.demand();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("smallContents")
    public void testSmallContentDontEmitInitialFirstDemand(Request.Content content) throws Exception
    {
        AtomicBoolean initial = new AtomicBoolean(true);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Request.Content.Subscription> subscriptionRef = new AtomicReference<>();
        Request.Content.Subscription subscription = content.subscribe((buffer, last, callback) ->
        {
            if (initial.getAndSet(false))
            {
                assertFalse(buffer.hasRemaining());
                assertFalse(last);
            }
            if (last)
                latch.countDown();
            else
                subscriptionRef.get().demand();
        }, false);
        subscriptionRef.set(subscription);

        // Initial demand.
        subscription.demand();

        assertTrue(latch.await(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @MethodSource("smallContents")
    public void testSmallContentFailedAfterFirstDemand(Request.Content content)
    {
        Throwable testFailure = new Throwable("test_failure");

        AtomicInteger notified = new AtomicInteger();
        AtomicReference<Throwable> failureRef = new AtomicReference<>();
        Request.Content.Subscription subscription = content.subscribe(new Request.Content.Consumer()
        {
            @Override
            public void onContent(ByteBuffer buffer, boolean last, Callback callback)
            {
                notified.getAndIncrement();
            }

            @Override
            public void onFailure(Throwable error)
            {
                testFailure.addSuppressed(new Throwable("suppressed"));
                failureRef.compareAndSet(null, error);
            }
        }, false);

        // Initial demand.
        subscription.demand();

        assertEquals(1, notified.get());

        subscription.fail(testFailure);
        subscription.demand();

        assertEquals(1, notified.get());
        Throwable failure = failureRef.get();
        assertNotNull(failure);
        assertSame(testFailure, failure);
        assertEquals(1, failure.getSuppressed().length);
    }

    @ParameterizedTest
    @MethodSource("smallContents")
    public void testDemandAfterLastContentFails(Request.Content content) throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Request.Content.Subscription> subscriptionRef = new AtomicReference<>();
        AtomicReference<Throwable> failureRef = new AtomicReference<>();
        Request.Content.Subscription subscription = content.subscribe(new Request.Content.Consumer()
        {
            @Override
            public void onContent(ByteBuffer buffer, boolean last, Callback callback)
            {
                if (last)
                    latch.countDown();
                else
                    subscriptionRef.get().demand();
            }

            @Override
            public void onFailure(Throwable error)
            {
                error.addSuppressed(new Throwable("suppressed"));
                failureRef.compareAndSet(null, error);
            }
        }, false);
        subscriptionRef.set(subscription);

        // Initial demand.
        subscription.demand();

        assertTrue(latch.await(5, TimeUnit.SECONDS));

        // Demand more, should fail.
        subscription.demand();

        Throwable failure = failureRef.get();
        assertNotNull(failure);
        assertEquals(1, failure.getSuppressed().length);
    }

    @ParameterizedTest
    @MethodSource("smallContents")
    public void testReproducibleContentCanHaveMultipleSubscriptions(Request.Content content) throws Exception
    {
        assumeTrue(content.isReproducible());

        CountDownLatch latch1 = new CountDownLatch(1);
        Request.Content.Subscription subscription1 = content.subscribe((buffer, last, callback) ->
        {
            if (last)
                latch1.countDown();
        }, true);

        CountDownLatch latch2 = new CountDownLatch(1);
        Request.Content.Subscription subscription2 = content.subscribe((buffer, last, callback) ->
        {
            if (last)
                latch2.countDown();
        }, true);

        // Initial demand.
        subscription1.demand();
        assertTrue(latch1.await(5, TimeUnit.SECONDS));

        // Initial demand.
        subscription2.demand();
        assertTrue(latch2.await(5, TimeUnit.SECONDS));
    }
}
