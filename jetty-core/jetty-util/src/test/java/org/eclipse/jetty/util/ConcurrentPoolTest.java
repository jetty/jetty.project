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

package org.eclipse.jetty.util;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.ToIntFunction;

import org.eclipse.jetty.logging.JettyLevel;
import org.eclipse.jetty.logging.JettyLogger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import static org.awaitility.Awaitility.await;
import static org.eclipse.jetty.util.ConcurrentPool.StrategyType.FIRST;
import static org.eclipse.jetty.util.ConcurrentPool.StrategyType.RANDOM;
import static org.eclipse.jetty.util.ConcurrentPool.StrategyType.ROUND_ROBIN;
import static org.eclipse.jetty.util.ConcurrentPool.StrategyType.THREAD_ID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ConcurrentPoolTest
{
    interface Factory
    {
        default ConcurrentPool<String> newPool(int maxEntries)
        {
            return newPool(maxEntries, pooled -> 1);
        }

        ConcurrentPool<String> newPool(int maxEntries, ToIntFunction<String> maxMultiplex);
    }

    public static List<Factory> factories()
    {
        return List.of(
            (maxEntries, maxMultiplex) -> new ConcurrentPool<>(FIRST, maxEntries, maxMultiplex, true),
            (maxEntries, maxMultiplex) -> new ConcurrentPool<>(RANDOM, maxEntries, maxMultiplex, true),
            (maxEntries, maxMultiplex) -> new ConcurrentPool<>(THREAD_ID, maxEntries, maxMultiplex, true),
            (maxEntries, maxMultiplex) -> new ConcurrentPool<>(ROUND_ROBIN, maxEntries, maxMultiplex, true)
        );
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testAcquireRelease(Factory factory)
    {
        ConcurrentPool<String> pool = factory.newPool(1);
        pool.reserve().enable("aaa", false);
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        Pool.Entry<String> e1 = pool.acquire();
        assertThat(e1.getPooled(), equalTo("aaa"));
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(0));
        assertThat(pool.getInUseCount(), is(1));

        assertNull(pool.acquire());

        assertThat(e1.release(), is(true));
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        assertThat(e1.release(), is(false));

        Pool.Entry<String> e2 = pool.acquire();
        assertThat(e2.getPooled(), equalTo("aaa"));
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(0));
        assertThat(pool.getInUseCount(), is(1));

        assertThat(e2.release(), is(true));
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        assertThat(e2.release(), is(false));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testRemoveBeforeRelease(Factory factory)
    {
        Pool<String> pool = factory.newPool(1);
        pool.reserve().enable("aaa", false);

        Pool.Entry<String> e1 = pool.acquire();
        assertThat(e1.remove(), is(true));
        assertThat(e1.remove(), is(false));
        assertThat(e1.release(), is(false));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testTerminateBeforeRelease(Factory factory)
    {
        Pool<String> pool = factory.newPool(1);
        pool.reserve().enable("aaa", false);

        Pool.Entry<String> e1 = pool.acquire();
        assertThat(pool.size(), is(1));

        Collection<Pool.Entry<String>> entries = pool.terminate();
        assertThat(pool.size(), is(0));
        assertThat(entries.size(), is(1));

        assertThat(e1.release(), is(false));
        assertThat(e1.remove(), is(true));
        assertThat(e1.remove(), is(false));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testMaxPoolSize(Factory factory)
    {
        Pool<String> pool = factory.newPool(1);
        assertThat(pool.size(), is(0));
        assertThat(pool.reserve(), notNullValue());
        assertThat(pool.size(), is(1));
        assertThat(pool.reserve(), nullValue());
        assertThat(pool.size(), is(1));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testReserve(Factory factory)
    {
        ConcurrentPool<String> pool = factory.newPool(2, pooled -> 2);

        // Reserve an entry
        Pool.Entry<String> e1 = pool.reserve();
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(1));
        assertThat(pool.getIdleCount(), is(0));
        assertThat(pool.getInUseCount(), is(0));

        assertThat(e1.release(), is(false));
        assertThat(pool.acquire(), is(nullValue()));

        // enable the entry
        assertThat(e1.enable("aaa", false), is(true));
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        // Reserve another entry
        Pool.Entry<String> e2 = pool.reserve();
        assertThat(pool.size(), is(2));
        assertThat(pool.getReservedCount(), is(1));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        // remove the reservation
        assertThat(e2.remove(), is(true));
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        // Reserve another entry
        Pool.Entry<String> e3 = pool.reserve();
        assertThat(pool.size(), is(2));
        assertThat(pool.getReservedCount(), is(1));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        // enable and acquire the entry
        e3.enable("bbb", true);
        assertThat(pool.size(), is(2));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(1));

        // can't reenable
        assertThrows(IllegalStateException.class, () -> e3.enable("xxx", false));

        // Can't enable acquired entry
        Pool.Entry<String> e = pool.acquire();
        assertThrows(IllegalStateException.class, () -> e.enable("xxx", false));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testReserveNegativeMaxPending(Factory factory)
    {
        Pool<String> pool = factory.newPool(2);
        assertThat(pool.reserve(), notNullValue());
        assertThat(pool.reserve(), notNullValue());
        assertThat(pool.reserve(), nullValue());
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testReserveAndRemove(Factory factory)
    {
        Pool<String> pool = factory.newPool(1);
        Pool.Entry<String> entry = pool.reserve();
        assertThat(entry, notNullValue());

        assertThat(entry.remove(), is(true));
        assertThat(entry.remove(), is(false));
        assertThat(entry.release(), is(false));

        assertThat(entry.enable("aaa", false), is(false));
        assertThat(entry.enable("aaa", true), is(false));

        Collection<Pool.Entry<String>> entries = pool.terminate();
        assertThat(entries.size(), is(0));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testTerminate(Factory factory)
    {
        Pool<String> pool = factory.newPool(1);
        Pool.Entry<String> entry = pool.reserve();
        assertThat(entry.enable("aaa", false), is(true));
        assertThat(pool.isTerminated(), is(false));

        Collection<Pool.Entry<String>> entries1 = pool.terminate();
        assertThat(pool.isTerminated(), is(true));
        assertThat(pool.size(), is(0));
        assertThat(entries1.size(), is(1));
        assertThat(entries1.iterator().next(), sameInstance(entry));

        Collection<Pool.Entry<String>> entries2 = pool.terminate();
        assertThat(pool.isTerminated(), is(true));
        assertThat(pool.size(), is(0));
        assertThat(entries2.size(), is(0));

        assertThat(pool.acquire(), nullValue());
        assertThat(pool.reserve(), nullValue());
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testTerminateMultiplexed(Factory factory)
    {
        Pool<String> pool = factory.newPool(1, pooled -> 2);
        pool.reserve().enable("aaa", false);

        Pool.Entry<String> e1 = pool.acquire();
        assertThat(e1, notNullValue());
        Pool.Entry<String> e2 = pool.acquire();
        assertThat(e2, notNullValue());
        assertThat(e2, sameInstance(e1));

        Collection<Pool.Entry<String>> entries = pool.terminate();
        assertThat(entries.size(), is(1));

        assertThat(e1.isInUse(), is(true));
        assertThat(e1.remove(), is(false));
        assertThat(e1.isInUse(), is(true));
        assertThat(e1.remove(), is(true));
        assertThat(e1.isInUse(), is(false));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testReserveAndTerminate(Factory factory)
    {
        Pool<String> pool = factory.newPool(1);
        Pool.Entry<String> entry = pool.reserve();

        Collection<Pool.Entry<String>> entries = pool.terminate();
        assertThat(entries.size(), is(1));
        assertThat(entries.iterator().next(), sameInstance(entry));

        assertThat(entry.enable("aaa", false), is(false));
        assertThat(entry.enable("bbb", true), is(false));

        assertThat(entry.release(), is(false));
        assertThat(entry.remove(), is(true));
        assertThat(entry.remove(), is(false));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testRemove(Factory factory)
    {
        Pool<String> pool = factory.newPool(1);
        pool.reserve().enable("aaa", false);

        Pool.Entry<String> e1 = pool.acquire();
        assertThat(e1.remove(), is(true));
        assertThat(e1.remove(), is(false));
        assertThat(e1.release(), is(false));
        assertThat(pool.acquire(), nullValue());
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testValuesSize(Factory factory)
    {
        Pool<String> pool = factory.newPool(2);

        assertThat(pool.size(), is(0));
        assertThat(pool.stream().count(), is(0L));
        pool.reserve().enable("aaa", false);
        pool.reserve().enable("bbb", false);
        List<String> objects = pool.stream()
            .map(Pool.Entry::getPooled)
            .toList();
        assertThat(objects, equalTo(Arrays.asList("aaa", "bbb")));
        assertThat(pool.size(), is(2));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testValuesContainsAcquiredEntries(Factory factory)
    {
        Pool<String> pool = factory.newPool(2);

        pool.reserve().enable("aaa", false);
        pool.reserve().enable("bbb", false);
        assertThat(pool.acquire(), notNullValue());
        assertThat(pool.acquire(), notNullValue());
        assertThat(pool.acquire(), nullValue());
        assertThat(pool.stream().count(), not(is(0)));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testMaxMultiplex(Factory factory)
    {
        Pool<String> pool = factory.newPool(2, pooled -> 3);

        Map<String, AtomicInteger> counts = new HashMap<>();
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        counts.put("a", a);
        counts.put("b", b);
        pool.reserve().enable("a", false);
        pool.reserve().enable("b", false);

        counts.get(pool.acquire().getPooled()).incrementAndGet();
        counts.get(pool.acquire().getPooled()).incrementAndGet();
        counts.get(pool.acquire().getPooled()).incrementAndGet();
        counts.get(pool.acquire().getPooled()).incrementAndGet();

        assertThat(a.get(), greaterThan(0));
        assertThat(a.get(), lessThanOrEqualTo(3));
        assertThat(b.get(), greaterThan(0));
        assertThat(b.get(), lessThanOrEqualTo(3));

        counts.get(pool.acquire().getPooled()).incrementAndGet();
        counts.get(pool.acquire().getPooled()).incrementAndGet();

        assertThat(a.get(), is(3));
        assertThat(b.get(), is(3));

        assertNull(pool.acquire());
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testRemoveMultiplexed(Factory factory)
    {
        Pool<String> pool = factory.newPool(1, pooled -> 2);
        pool.reserve().enable("aaa", false);

        Pool.Entry<String> e1 = pool.acquire();
        assertThat(e1, notNullValue());
        Pool.Entry<String> e2 = pool.acquire();
        assertThat(e2, notNullValue());
        assertThat(e2, sameInstance(e1));

        assertThat(pool.stream().findFirst().orElseThrow().isIdle(), is(false));

        assertThat(e1.remove(), is(false));
        assertThat(pool.stream().findFirst().orElseThrow().isIdle(), is(false));
        assertThat(pool.stream().findFirst().orElseThrow().isTerminated(), is(true));
        assertThat(e1.remove(), is(true));
        assertThat(pool.size(), is(0));

        assertThat(e1.remove(), is(false));
        assertThat(e1.release(), is(false));
        assertThat(e1.remove(), is(false));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testMultiplexRemoveThenAcquireThenReleaseRemove(Factory factory)
    {
        Pool<String> pool = factory.newPool(1, pooled -> 2);
        pool.reserve().enable("aaa", false);

        Pool.Entry<String> e1 = pool.acquire();
        Pool.Entry<String> e2 = pool.acquire();

        assertThat(e1.remove(), is(false));
        assertThat(e1.isTerminated(), is(true));
        assertThat(pool.acquire(), nullValue());
        assertThat(e2.release(), is(false));
        assertThat(e2.remove(), is(true));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testNonMultiplexRemoveAfterAcquire(Factory factory)
    {
        Pool<String> pool = factory.newPool(1, pooled -> 2);
        pool.reserve().enable("aaa", false);

        Pool.Entry<String> e1 = pool.acquire();
        assertThat(e1.remove(), is(true));
        assertThat(pool.size(), is(0));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testMultiplexRemoveAfterAcquire(Factory factory)
    {
        Pool<String> pool = factory.newPool(1, pooled -> 3);
        pool.reserve().enable("aaa", false);

        Pool.Entry<String> e1 = pool.acquire();
        Pool.Entry<String> e2 = pool.acquire();
        Pool.Entry<String> e3 = pool.acquire();

        assertThat(e1.remove(), is(false));
        assertThat(e2.remove(), is(false));
        assertThat(e3.remove(), is(true));
        assertThat(pool.size(), is(0));

        assertThat(e1.release(), is(false));
        assertThat(pool.size(), is(0));

        Pool.Entry<String> e4 = pool.acquire();
        assertThat(e4, nullValue());

        assertThat(e2.release(), is(false));
        assertThat(pool.size(), is(0));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testReleaseThenRemoveNonEnabledEntry(Factory factory)
    {
        Pool<String> pool = factory.newPool(1);
        Pool.Entry<String> e = pool.reserve();
        assertThat(pool.size(), is(1));
        assertThat(e.release(), is(false));
        assertThat(pool.size(), is(1));
        assertThat(e.remove(), is(true));
        assertThat(pool.size(), is(0));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testRemoveNonEnabledEntry(Factory factory)
    {
        Pool<String> pool = factory.newPool(1);
        Pool.Entry<String> e = pool.reserve();
        assertThat(pool.size(), is(1));
        assertThat(e.remove(), is(true));
        assertThat(pool.size(), is(0));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testAcquireWithCreator(Factory factory)
    {
        ConcurrentPool<String> pool = factory.newPool(2);

        assertThat(pool.size(), is(0));
        assertThat(pool.acquire(e -> null), nullValue());
        assertThat(pool.size(), is(0));

        Pool.Entry<String> e1 = pool.acquire(e -> "e1");
        assertThat(e1.getPooled(), is("e1"));
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getInUseCount(), is(1));

        assertThat(pool.acquire(e -> null), nullValue());
        assertThat(pool.size(), is(1));

        Pool.Entry<String> e2 = pool.acquire(e -> "e2");
        assertThat(e2.getPooled(), is("e2"));
        assertThat(pool.size(), is(2));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getInUseCount(), is(2));

        Pool.Entry<String> e3 = pool.acquire(e -> "e3");
        assertThat(e3, nullValue());
        assertThat(pool.size(), is(2));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getInUseCount(), is(2));

        assertThat(pool.acquire(e ->
        {
            throw new IllegalStateException();
        }), nullValue());

        e2.release();
        assertThat(pool.size(), is(2));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getInUseCount(), is(1));

        Pool.Entry<String> e4 = pool.acquire(e -> "e4");
        assertThat(e4.getPooled(), is("e2"));
        assertThat(pool.size(), is(2));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getInUseCount(), is(2));

        e1.remove();
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getInUseCount(), is(1));

        assertThrows(IllegalStateException.class, () -> pool.acquire(e ->
        {
            throw new IllegalStateException();
        }));
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getInUseCount(), is(1));
    }

    @Test
    public void testRoundRobinStrategy()
    {
        ConcurrentPool<AtomicInteger> pool = new ConcurrentPool<>(ROUND_ROBIN, 4);

        Pool.Entry<AtomicInteger> e1 = pool.acquire(e -> new AtomicInteger());
        Pool.Entry<AtomicInteger> e2 = pool.acquire(e -> new AtomicInteger());
        Pool.Entry<AtomicInteger> e3 = pool.acquire(e -> new AtomicInteger());
        Pool.Entry<AtomicInteger> e4 = pool.acquire(e -> new AtomicInteger());
        assertNull(pool.acquire(e -> new AtomicInteger()));

        e1.release();
        e2.release();
        e3.release();
        e4.release();

        Pool.Entry<AtomicInteger> last = null;
        for (int i = 0; i < 8; i++)
        {
            Pool.Entry<AtomicInteger> e = pool.acquire();
            if (last != null)
                assertThat(e, not(sameInstance(last)));
            e.getPooled().incrementAndGet();
            e.release();
            last = e;
        }

        assertThat(e1.getPooled().get(), is(2));
        assertThat(e2.getPooled().get(), is(2));
        assertThat(e3.getPooled().get(), is(2));
        assertThat(e4.getPooled().get(), is(2));
    }

    @Test
    public void testRandomStrategy()
    {
        ConcurrentPool<AtomicInteger> pool = new ConcurrentPool<>(RANDOM, 4);

        Pool.Entry<AtomicInteger> e1 = pool.acquire(e -> new AtomicInteger());
        Pool.Entry<AtomicInteger> e2 = pool.acquire(e -> new AtomicInteger());
        Pool.Entry<AtomicInteger> e3 = pool.acquire(e -> new AtomicInteger());
        Pool.Entry<AtomicInteger> e4 = pool.acquire(e -> new AtomicInteger());
        assertNull(pool.acquire(e -> new AtomicInteger()));

        e1.release();
        e2.release();
        e3.release();
        e4.release();

        for (int i = 0; i < 400; i++)
        {
            Pool.Entry<AtomicInteger> e = pool.acquire();
            e.getPooled().incrementAndGet();
            e.release();
        }

        assertThat(e1.getPooled().get(), greaterThan(10));
        assertThat(e2.getPooled().get(), greaterThan(10));
        assertThat(e3.getPooled().get(), greaterThan(10));
        assertThat(e4.getPooled().get(), greaterThan(10));
    }

    private void waitForGC(ConcurrentPool<String> pool, int size)
    {
        await().atMost(5, TimeUnit.SECONDS).until(() ->
        {
            System.gc();
            pool.sweep();
            return pool.size();
        }, is(size));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testLeakReserved(Factory factory)
    {
        ConcurrentPool<String> pool = factory.newPool(3);
        Pool.Entry<String> e0 = pool.reserve();
        Pool.Entry<String> e1 = pool.reserve();
        Pool.Entry<String> e2 = pool.reserve();
        assertThat(e0, notNullValue());
        assertThat(e1, notNullValue());
        assertThat(e2, notNullValue());

        assertThat(pool.reserve(), nullValue());
        assertThat(pool.size(), is(3));
        assertThat(pool.getReservedCount(), is(3));
        assertThat(pool.getIdleCount(), is(0));
        assertThat(pool.getInUseCount(), is(0));

        e0 = null;
        waitForGC(pool, 2);
        assertThat(pool.size(), is(2));
        assertThat(pool.getReservedCount(), is(2));
        assertThat(pool.getIdleCount(), is(0));
        assertThat(pool.getInUseCount(), is(0));

        assertThat(e0, nullValue());
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testLeakEnabled(Factory factory)
    {
        ConcurrentPool<String> pool = factory.newPool(10);
        pool.reserve().enable("idle", false);
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        Pool.Entry<String> e1 = pool.reserve();
        e1.enable("held", true);
        assertThat(e1.getPooled(), equalTo("held"));
        assertThat(pool.size(), is(2));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(1));

        Pool.Entry<String> e2 = pool.reserve();
        e2.enable("leaked", true);
        assertThat(e2.getPooled(), equalTo("leaked"));
        assertThat(pool.size(), is(3));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(2));
        e2 = null;
        waitForGC(pool, 2);

        assertThat(pool.size(), is(2));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(1));

        e1 = null;
        waitForGC(pool, 1);
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        Pool.Entry<String> e0 = pool.acquire();
        assertThat(e0.getPooled(), equalTo("idle"));
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(0));
        assertThat(pool.getInUseCount(), is(1));

        e0 = null;
        waitForGC(pool, 0);
        assertThat(pool.size(), is(0));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(0));
        assertThat(pool.getInUseCount(), is(0));

        assertThat(e0, nullValue());
        assertThat(e1, nullValue());
        assertThat(e2, nullValue());
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testSweepOnReserve(Factory factory)
    {
        ConcurrentPool<String> pool = factory.newPool(3);
        Pool.Entry<String> e0 = pool.reserve();
        assertThat(e0, notNullValue());
        Pool.Entry<String> e1 = pool.reserve();
        assertThat(e1, notNullValue());
        Pool.Entry<String> e2 = pool.reserve();
        assertThat(e2, notNullValue());

        assertThat(pool.reserve(), nullValue());
        assertThat(pool.size(), is(3));
        assertThat(pool.getReservedCount(), is(3));
        assertThat(pool.getIdleCount(), is(0));
        assertThat(pool.getInUseCount(), is(0));

        e0 = null;
        AtomicReference<Pool.Entry<String>> entry = new AtomicReference<>();
        await().atMost(5, TimeUnit.SECONDS).until(() ->
        {
            System.gc();
            entry.set(pool.reserve());
            return entry.get();
        }, notNullValue());

        Pool.Entry<String> e3 = entry.get();
        assertThat(e3, notNullValue());

        e1.enable("one", false);
        e2.enable("two", false);
        e3.enable("three", true);
        assertThat(pool.size(), is(3));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(2));
        assertThat(pool.getInUseCount(), is(1));

        assertThat(e0, nullValue());
    }

    @Test
    public void testLeakDebug()
    {
        if (LoggerFactory.getLogger(ConcurrentPool.class) instanceof JettyLogger jettyLogger)
        {
            jettyLogger.setLevel(JettyLevel.DEBUG);
            jettyLogger.setHideStacks(true);

            List<Throwable> history = new CopyOnWriteArrayList<>();

            ConcurrentPool<String> pool = new ConcurrentPool<>(RANDOM, 10, p -> 1, true)
            {
                @Override
                void leaked(Holder<String> holder)
                {
                    if (holder instanceof ConcurrentPool.DebugWeakHolder<String> debugWeakHolder)
                    {
                        history.add(debugWeakHolder.getLastFreed());
                    }
                }
            };
            Pool.Entry<String> e0 = pool.reserve();
            Pool.Entry<String> e1 = pool.reserve();
            e1.enable("test", true);
            assertThat(e0, notNullValue());
            assertThat(e1, notNullValue());

            assertThat(pool.size(), is(2));
            assertThat(pool.getReservedCount(), is(1));
            assertThat(pool.getIdleCount(), is(0));
            assertThat(pool.getInUseCount(), is(1));

            e0 = null;
            e1 = null;
            waitForGC(pool, 0);
            assertThat(pool.size(), is(0));
            assertThat(pool.getReservedCount(), is(0));
            assertThat(pool.getIdleCount(), is(0));
            assertThat(pool.getInUseCount(), is(0));
            assertThat(e0, nullValue());
            assertThat(e1, nullValue());

            assertThat(history.size(), is(2));
            assertThat(history.toString(), containsString("pooled=null"));
            assertThat(history.toString(), containsString("pooled=test"));
            assertThat(history.toString(), containsString(Thread.currentThread().getName() + ":"));
            jettyLogger.setLevel(JettyLevel.WARN);
        }
    }
}
