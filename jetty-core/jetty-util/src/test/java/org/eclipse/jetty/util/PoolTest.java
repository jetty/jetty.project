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

package org.eclipse.jetty.util;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static java.util.stream.Collectors.toList;
import static org.eclipse.jetty.util.Pool.StrategyType.FIRST;
import static org.eclipse.jetty.util.Pool.StrategyType.RANDOM;
import static org.eclipse.jetty.util.Pool.StrategyType.ROUND_ROBIN;
import static org.hamcrest.MatcherAssert.assertThat;
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

public class PoolTest
{
    private static class Pooled implements Closeable
    {
        private final String value;
        private boolean closed;

        private Pooled(String value)
        {
            this.value = value;
        }

        @Override
        public void close() throws IOException
        {
            closed = true;
        }
    }

    interface Factory
    {
        default Pool<Pooled> newPool(int maxEntries)
        {
            return newPool(maxEntries, pooled -> 1);
        }

        Pool<Pooled> newPool(int maxEntries, ToIntFunction<Pooled> maxMultiplex);
    }

    public static List<Factory> factories()
    {
        return List.of(
            (maxEntries, maxMultiplex) -> new Pool<>(FIRST, maxEntries, false, maxMultiplex),
            (maxEntries, maxMultiplex) -> new Pool<>(FIRST, maxEntries, true, maxMultiplex),
            (maxEntries, maxMultiplex) -> new Pool<>(RANDOM, maxEntries, false, maxMultiplex),
            (maxEntries, maxMultiplex) -> new Pool<>(ROUND_ROBIN, maxEntries, false, maxMultiplex)
        );
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testAcquireRelease(Factory factory)
    {
        Pool<Pooled> pool = factory.newPool(1);
        pool.reserve().enable(new Pooled("aaa"), false);
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        Pool<Pooled>.Entry e1 = pool.acquire();
        assertThat(e1.getPooled().value, equalTo("aaa"));
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

        Pool<Pooled>.Entry e2 = pool.acquire();
        assertThat(e2.getPooled().value, equalTo("aaa"));
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
        Pool<Pooled> pool = factory.newPool(1);
        pool.reserve().enable(new Pooled("aaa"), false);

        Pool<Pooled>.Entry e1 = pool.acquire();
        assertThat(e1.remove(), is(true));
        assertThat(e1.remove(), is(false));
        assertThat(e1.release(), is(false));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testCloseBeforeRelease(Factory factory)
    {
        Pool<Pooled> pool = factory.newPool(1);
        pool.reserve().enable(new Pooled("aaa"), false);

        Pool<Pooled>.Entry e1 = pool.acquire();
        assertThat(pool.size(), is(1));
        pool.close();
        assertThat(pool.size(), is(0));
        assertThat(e1.release(), is(false));
        assertThat(e1.getPooled().closed, is(true));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testMaxPoolSize(Factory factory)
    {
        Pool<Pooled> pool = factory.newPool(1);
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
        Pool<Pooled> pool = factory.newPool(2, pooled -> 2);

        // Reserve an entry
        Pool<Pooled>.Entry e1 = pool.reserve();
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(1));
        assertThat(pool.getIdleCount(), is(0));
        assertThat(pool.getInUseCount(), is(0));

        // enable the entry
        e1.enable(new Pooled("aaa"), false);
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        // Reserve another entry
        Pool<Pooled>.Entry e2 = pool.reserve();
        assertThat(pool.size(), is(2));
        assertThat(pool.getReservedCount(), is(1));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        // remove the reservation
        e2.remove();
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        // Reserve another entry
        Pool<Pooled>.Entry e3 = pool.reserve();
        assertThat(pool.size(), is(2));
        assertThat(pool.getReservedCount(), is(1));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        // enable and acquire the entry
        e3.enable(new Pooled("bbb"), true);
        assertThat(pool.size(), is(2));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(1));

        // can't reenable
        assertThrows(IllegalStateException.class, () -> e3.enable(new Pooled("xxx"), false));

        // Can't enable acquired entry
        Pool<Pooled>.Entry e = pool.acquire();
        assertThrows(IllegalStateException.class, () -> e.enable(new Pooled("xxx"), false));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testReserveNegativeMaxPending(Factory factory)
    {
        Pool<Pooled> pool = factory.newPool(2);
        assertThat(pool.reserve(), notNullValue());
        assertThat(pool.reserve(), notNullValue());
        assertThat(pool.reserve(), nullValue());
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testClose(Factory factory)
    {
        Pool<Pooled> pool = factory.newPool(1);
        Pooled holder = new Pooled("aaa");
        pool.reserve().enable(holder, false);
        assertThat(pool.isClosed(), is(false));
        pool.close();
        pool.close();

        assertThat(pool.isClosed(), is(true));
        assertThat(pool.size(), is(0));
        assertThat(pool.acquire(), nullValue());
        assertThat(pool.reserve(), nullValue());
        assertThat(holder.closed, is(true));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testRemove(Factory factory)
    {
        Pool<Pooled> pool = factory.newPool(1);
        pool.reserve().enable(new Pooled("aaa"), false);

        Pool<Pooled>.Entry e1 = pool.acquire();
        assertThat(e1.remove(), is(true));
        assertThat(e1.remove(), is(false));
        assertThat(e1.release(), is(false));
        assertThat(pool.acquire(), nullValue());
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testValuesSize(Factory factory)
    {
        Pool<Pooled> pool = factory.newPool(2);

        assertThat(pool.size(), is(0));
        assertThat(pool.values().isEmpty(), is(true));
        pool.reserve().enable(new Pooled("aaa"), false);
        pool.reserve().enable(new Pooled("bbb"), false);
        assertThat(pool.values().stream().map(Pool.Entry::getPooled).map(closeableHolder -> closeableHolder.value).collect(toList()), equalTo(Arrays.asList("aaa", "bbb")));
        assertThat(pool.size(), is(2));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testValuesContainsAcquiredEntries(Factory factory)
    {
        Pool<Pooled> pool = factory.newPool(2);

        pool.reserve().enable(new Pooled("aaa"), false);
        pool.reserve().enable(new Pooled("bbb"), false);
        assertThat(pool.acquire(), notNullValue());
        assertThat(pool.acquire(), notNullValue());
        assertThat(pool.acquire(), nullValue());
        assertThat(pool.values().isEmpty(), is(false));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testMaxMultiplex(Factory factory)
    {
        Pool<Pooled> pool = factory.newPool(2, pooled -> 3);

        Map<String, AtomicInteger> counts = new HashMap<>();
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        counts.put("a", a);
        counts.put("b", b);
        pool.reserve().enable(new Pooled("a"), false);
        pool.reserve().enable(new Pooled("b"), false);

        counts.get(pool.acquire().getPooled().value).incrementAndGet();
        counts.get(pool.acquire().getPooled().value).incrementAndGet();
        counts.get(pool.acquire().getPooled().value).incrementAndGet();
        counts.get(pool.acquire().getPooled().value).incrementAndGet();

        assertThat(a.get(), greaterThan(0));
        assertThat(a.get(), lessThanOrEqualTo(3));
        assertThat(b.get(), greaterThan(0));
        assertThat(b.get(), lessThanOrEqualTo(3));

        counts.get(pool.acquire().getPooled().value).incrementAndGet();
        counts.get(pool.acquire().getPooled().value).incrementAndGet();

        assertThat(a.get(), is(3));
        assertThat(b.get(), is(3));

        assertNull(pool.acquire());
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testRemoveMultiplexed(Factory factory)
    {
        Pool<Pooled> pool = factory.newPool(1, pooled -> 2);
        pool.reserve().enable(new Pooled("aaa"), false);

        Pool<Pooled>.Entry e1 = pool.acquire();
        assertThat(e1, notNullValue());
        Pool<Pooled>.Entry e2 = pool.acquire();
        assertThat(e2, notNullValue());
        assertThat(e2, sameInstance(e1));

        assertThat(pool.values().stream().findFirst().orElseThrow().isIdle(), is(false));

        assertThat(e1.remove(), is(false));
        assertThat(pool.values().stream().findFirst().orElseThrow().isIdle(), is(false));
        assertThat(pool.values().stream().findFirst().orElseThrow().isClosed(), is(true));
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
        Pool<Pooled> pool = factory.newPool(1, pooled -> 2);
        pool.reserve().enable(new Pooled("aaa"), false);

        Pool<Pooled>.Entry e1 = pool.acquire();
        Pool<Pooled>.Entry e2 = pool.acquire();

        assertThat(e1.remove(), is(false));
        assertThat(e1.isClosed(), is(true));
        assertThat(pool.acquire(), nullValue());
        assertThat(e2.release(), is(false));
        assertThat(e2.remove(), is(true));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testNonMultiplexRemoveAfterAcquire(Factory factory)
    {
        Pool<Pooled> pool = factory.newPool(1, pooled -> 2);
        pool.reserve().enable(new Pooled("aaa"), false);

        Pool<Pooled>.Entry e1 = pool.acquire();
        assertThat(e1.remove(), is(true));
        assertThat(pool.size(), is(0));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testMultiplexRemoveAfterAcquire(Factory factory)
    {
        Pool<Pooled> pool = factory.newPool(1, pooled -> 2);
        pool.reserve().enable(new Pooled("aaa"), false);

        Pool<Pooled>.Entry e1 = pool.acquire();
        Pool<Pooled>.Entry e2 = pool.acquire();

        assertThat(e1.remove(), is(false));
        assertThat(e2.remove(), is(true));
        assertThat(pool.size(), is(0));

        assertThat(e1.release(), is(false));
        assertThat(pool.size(), is(0));

        Pool<Pooled>.Entry e3 = pool.acquire();
        assertThat(e3, nullValue());

        assertThat(e2.release(), is(false));
        assertThat(pool.size(), is(0));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testReleaseThenRemoveNonEnabledEntry(Factory factory)
    {
        Pool<Pooled> pool = factory.newPool(1);
        Pool<Pooled>.Entry e = pool.reserve();
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
        Pool<Pooled> pool = factory.newPool(1);
        Pool<Pooled>.Entry e = pool.reserve();
        assertThat(pool.size(), is(1));
        assertThat(e.remove(), is(true));
        assertThat(pool.size(), is(0));
    }

    @ParameterizedTest
    @MethodSource(value = "factories")
    public void testAcquireWithCreator(Factory factory)
    {
        Pool<Pooled> pool = factory.newPool(2);

        assertThat(pool.size(), is(0));
        assertThat(pool.acquire(e -> null), nullValue());
        assertThat(pool.size(), is(0));

        Pool<Pooled>.Entry e1 = pool.acquire(e -> new Pooled("e1"));
        assertThat(e1.getPooled().value, is("e1"));
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getInUseCount(), is(1));

        assertThat(pool.acquire(e -> null), nullValue());
        assertThat(pool.size(), is(1));

        Pool<Pooled>.Entry e2 = pool.acquire(e -> new Pooled("e2"));
        assertThat(e2.getPooled().value, is("e2"));
        assertThat(pool.size(), is(2));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getInUseCount(), is(2));

        Pool<Pooled>.Entry e3 = pool.acquire(e -> new Pooled("e3"));
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

        Pool<Pooled>.Entry e4 = pool.acquire(e -> new Pooled("e4"));
        assertThat(e4.getPooled().value, is("e2"));
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
        Pool<AtomicInteger> pool = new Pool<>(ROUND_ROBIN, 4);

        Pool<AtomicInteger>.Entry e1 = pool.acquire(e -> new AtomicInteger());
        Pool<AtomicInteger>.Entry e2 = pool.acquire(e -> new AtomicInteger());
        Pool<AtomicInteger>.Entry e3 = pool.acquire(e -> new AtomicInteger());
        Pool<AtomicInteger>.Entry e4 = pool.acquire(e -> new AtomicInteger());
        assertNull(pool.acquire(e -> new AtomicInteger()));

        e1.release();
        e2.release();
        e3.release();
        e4.release();

        Pool<AtomicInteger>.Entry last = null;
        for (int i = 0; i < 8; i++)
        {
            Pool<AtomicInteger>.Entry e = pool.acquire();
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
        Pool<AtomicInteger> pool = new Pool<>(RANDOM, 4);

        Pool<AtomicInteger>.Entry e1 = pool.acquire(e -> new AtomicInteger());
        Pool<AtomicInteger>.Entry e2 = pool.acquire(e -> new AtomicInteger());
        Pool<AtomicInteger>.Entry e3 = pool.acquire(e -> new AtomicInteger());
        Pool<AtomicInteger>.Entry e4 = pool.acquire(e -> new AtomicInteger());
        assertNull(pool.acquire(e -> new AtomicInteger()));

        e1.release();
        e2.release();
        e3.release();
        e4.release();

        for (int i = 0; i < 400; i++)
        {
            Pool<AtomicInteger>.Entry e = pool.acquire();
            e.getPooled().incrementAndGet();
            e.release();
        }

        assertThat(e1.getPooled().get(), greaterThan(10));
        assertThat(e2.getPooled().get(), greaterThan(10));
        assertThat(e3.getPooled().get(), greaterThan(10));
        assertThat(e4.getPooled().get(), greaterThan(10));
    }
}
