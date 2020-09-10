//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static java.util.stream.Collectors.toList;
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
    public static Stream<Object[]> strategy()
    {
        List<Object[]> data = new ArrayList<>();
        data.add(new Object[]{null});
        data.add(new Object[]{new Pool.LinearSearchStrategy<>()});
        data.add(new Object[]{new Pool.CompositeStrategy<>(new Pool.RandomStrategy<>(), new Pool.LinearSearchStrategy<>())});
        data.add(new Object[]{new Pool.CompositeStrategy<>(new Pool.ThreadIdStrategy<>(), new Pool.LinearSearchStrategy<>())});
        data.add(new Object[]{new Pool.CompositeStrategy<>(new Pool.ThreadLocalStrategy<>(), new Pool.LinearSearchStrategy<>())});
        data.add(new Object[]{new Pool.CompositeStrategy<>(new Pool.ThreadLocalListStrategy<>(2), new Pool.LinearSearchStrategy<>())});
        data.add(new Object[]{new Pool.RandomIterationStrategy<>()});
        data.add(new Object[]{new Pool.ThreadLocalIteratorStrategy<>(false)});
        data.add(new Object[]{new Pool.ThreadLocalIteratorStrategy<>(true)});
        data.add(new Object[]{new Pool.CompositeStrategy<>(new Pool.RoundRobinStrategy<>(), new Pool.LinearSearchStrategy<>())});
        data.add(new Object[]{new Pool.RetryStategy<>(new Pool.RoundRobinStrategy<>())});
        data.add(new Object[]{new Pool.RoundRobinIterationStrategy<>()});
        data.add(new Object[]{new Pool.LeastRecentlyUsedStrategy<>()});
        data.add(new Object[]{new Pool.OneStrategyToRuleThemAll<>(Pool.OneStrategyToRuleThemAll.Mode.LINEAR)});
        data.add(new Object[]{new Pool.OneStrategyToRuleThemAll<>(Pool.OneStrategyToRuleThemAll.Mode.RANDOM)});
        data.add(new Object[]{new Pool.OneStrategyToRuleThemAll<>(Pool.OneStrategyToRuleThemAll.Mode.THREAD_LOCAL)});
        data.add(new Object[]{new Pool.OneStrategyToRuleThemAll<>(Pool.OneStrategyToRuleThemAll.Mode.ROUND_ROBIN)});
        return data.stream();
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testAcquireRelease(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(1, strategy);
        pool.reserve(-1).enable("aaa", false);
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        Pool<String>.Entry e1 = pool.acquire();
        assertThat(e1.getPooled(), equalTo("aaa"));
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(0));
        assertThat(pool.getInUseCount(), is(1));

        assertNull(pool.acquire());

        e1.release();
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        assertThrows(IllegalStateException.class, e1::release);

        Pool<String>.Entry e2 = pool.acquire();
        assertThat(e2.getPooled(), equalTo("aaa"));
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(0));
        assertThat(pool.getInUseCount(), is(1));

        pool.release(e2);
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        assertThrows(IllegalStateException.class, () -> pool.release(e2));
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testRemoveBeforeRelease(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(1, strategy);
        pool.reserve(-1).enable("aaa", false);

        Pool<String>.Entry e1 = pool.acquire();
        assertThat(pool.remove(e1), is(true));
        assertThat(pool.remove(e1), is(false));
        assertThat(pool.release(e1), is(false));
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testCloseBeforeRelease(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(1, strategy);
        pool.reserve(-1).enable("aaa", false);

        Pool<String>.Entry e1 = pool.acquire();
        assertThat(pool.size(), is(1));
        pool.close();
        assertThat(pool.size(), is(0));
        assertThat(pool.release(e1), is(false));
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testMaxPoolSize(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(1, strategy);
        assertThat(pool.size(), is(0));
        assertThat(pool.reserve(-1), notNullValue());
        assertThat(pool.size(), is(1));
        assertThat(pool.reserve(-1), nullValue());
        assertThat(pool.size(), is(1));
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testReserve(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(2, strategy);

        // Reserve an entry
        Pool<String>.Entry e1 = pool.reserve(-1);
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(1));
        assertThat(pool.getIdleCount(), is(0));
        assertThat(pool.getInUseCount(), is(0));

        // max reservations
        assertNull(pool.reserve(1));
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(1));
        assertThat(pool.getIdleCount(), is(0));
        assertThat(pool.getInUseCount(), is(0));

        // enable the entry
        e1.enable("aaa", false);
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        // Reserve another entry
        Pool<String>.Entry e2 = pool.reserve(-1);
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
        Pool<String>.Entry e3 = pool.reserve(-1);
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
        assertThat(pool.acquire(), is(e1));
        assertThrows(IllegalStateException.class, () -> e1.enable("xxx", false));
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testReserveMaxPending(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(2, strategy);
        assertThat(pool.reserve(0), nullValue());
        assertThat(pool.reserve(1), notNullValue());
        assertThat(pool.reserve(1), nullValue());
        assertThat(pool.reserve(2), notNullValue());
        assertThat(pool.reserve(2), nullValue());
        assertThat(pool.reserve(3), nullValue());
        assertThat(pool.reserve(-1), nullValue());
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testReserveNegativeMaxPending(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(2, strategy);
        assertThat(pool.reserve(-1), notNullValue());
        assertThat(pool.reserve(-1), notNullValue());
        assertThat(pool.reserve(-1), nullValue());
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testClose(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(1, strategy);
        pool.reserve(-1).enable("aaa", false);
        assertThat(pool.isClosed(), is(false));
        pool.close();
        pool.close();

        assertThat(pool.isClosed(), is(true));
        assertThat(pool.size(), is(0));
        assertThat(pool.acquire(), nullValue());
        assertThat(pool.reserve(-1), nullValue());
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testClosingCloseable(Pool.Strategy<String> strategy)
    {
        AtomicBoolean closed = new AtomicBoolean();
        Pool<Closeable> pool = new Pool<>(1,0);
        Closeable pooled = () -> closed.set(true);
        pool.reserve(-1).enable(pooled, false);
        assertThat(closed.get(), is(false));
        pool.close();
        assertThat(closed.get(), is(true));
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testRemove(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(1, strategy);
        pool.reserve(-1).enable("aaa", false);

        Pool<String>.Entry e1 = pool.acquire();
        assertThat(pool.remove(e1), is(true));
        assertThat(pool.remove(e1), is(false));
        assertThat(pool.release(e1), is(false));
        assertThat(pool.acquire(), nullValue());
        assertThrows(NullPointerException.class, () -> pool.remove(null));
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testValuesSize(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(2, strategy);

        assertThat(pool.size(), is(0));
        assertThat(pool.values().isEmpty(), is(true));
        pool.reserve(-1).enable("aaa", false);
        pool.reserve(-1).enable("bbb", false);
        assertThat(pool.values().stream().map(Pool.Entry::getPooled).collect(toList()), equalTo(Arrays.asList("aaa", "bbb")));
        assertThat(pool.size(), is(2));
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testValuesContainsAcquiredEntries(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(2, strategy);

        pool.reserve(-1).enable("aaa", false);
        pool.reserve(-1).enable("bbb", false);
        assertThat(pool.acquire(), notNullValue());
        assertThat(pool.acquire(), notNullValue());
        assertThat(pool.acquire(), nullValue());
        assertThat(pool.values().isEmpty(), is(false));
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testAcquireAt(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(2, strategy);

        pool.reserve(-1).enable("aaa", false);
        pool.reserve(-1).enable("bbb", false);

        assertThat(pool.acquireAt(2), nullValue());
        assertThat(pool.acquireAt(0), notNullValue());
        assertThat(pool.acquireAt(0), nullValue());
        assertThat(pool.acquireAt(1), notNullValue());
        assertThat(pool.acquireAt(1), nullValue());
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testMaxUsageCount(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(1, strategy);
        pool.setMaxUsageCount(3);
        pool.reserve(-1).enable("aaa", false);

        Pool<String>.Entry e1 = pool.acquire();
        assertThat(pool.release(e1), is(true));
        e1 = pool.acquire();
        assertThat(pool.release(e1), is(true));
        e1 = pool.acquire();
        assertThat(pool.release(e1), is(false));
        assertThat(pool.acquire(), nullValue());
        assertThat(pool.size(), is(1));
        assertThat(pool.remove(e1), is(true));
        assertThat(pool.remove(e1), is(false));
        assertThat(pool.size(), is(0));
        Pool<String>.Entry e1Copy = e1;
        assertThat(pool.release(e1Copy), is(false));
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testMaxMultiplex(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(2, strategy);
        pool.setMaxMultiplex(3);

        Map<String, AtomicInteger> counts = new HashMap<>();
        AtomicInteger a = new AtomicInteger();
        AtomicInteger b = new AtomicInteger();
        counts.put("a", a);
        counts.put("b", b);
        pool.reserve(-1).enable("a", false);
        pool.reserve(-1).enable("b", false);

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
    @MethodSource(value = "strategy")
    public void testRemoveMultiplexed(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(1, strategy);
        pool.setMaxMultiplex(2);
        pool.reserve(-1).enable("aaa", false);

        Pool<String>.Entry e1 = pool.acquire();
        Pool<String>.Entry e2 = pool.acquire();
        assertThat(pool.values().stream().findFirst().get().isIdle(), is(false));

        assertThat(pool.remove(e1), is(false));
        assertThat(pool.values().stream().findFirst().get().isIdle(), is(false));
        assertThat(pool.values().stream().findFirst().get().isClosed(), is(true));
        assertThat(pool.remove(e1), is(true));
        assertThat(pool.size(), is(0));

        assertThat(pool.remove(e1), is(false));

        assertThat(pool.release(e1), is(false));

        assertThat(pool.remove(e1), is(false));
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testMultiplexRemoveThenAcquireThenReleaseRemove(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(1, strategy);
        pool.setMaxMultiplex(2);
        pool.reserve(-1).enable("aaa", false);

        Pool<String>.Entry e1 = pool.acquire();
        Pool<String>.Entry e2 = pool.acquire();

        assertThat(pool.remove(e1), is(false));
        assertThat(e1.isClosed(), is(true));
        assertThat(pool.acquire(), nullValue());
        assertThat(pool.release(e2), is(false));
        assertThat(pool.remove(e2), is(true));
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testNonMultiplexRemoveAfterAcquire(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(1, strategy);
        pool.setMaxMultiplex(2);
        pool.reserve(-1).enable("aaa", false);

        Pool<String>.Entry e1 = pool.acquire();
        assertThat(pool.remove(e1), is(true));
        assertThat(pool.size(), is(0));
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testMultiplexRemoveAfterAcquire(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(1, strategy);
        pool.setMaxMultiplex(2);
        pool.reserve(-1).enable("aaa", false);

        Pool<String>.Entry e1 = pool.acquire();
        Pool<String>.Entry e2 = pool.acquire();

        assertThat(pool.remove(e1), is(false));
        assertThat(pool.remove(e2), is(true));
        assertThat(pool.size(), is(0));

        assertThat(pool.release(e1), is(false));
        assertThat(pool.size(), is(0));

        Pool<String>.Entry e3 = pool.acquire();
        assertThat(e3, nullValue());

        assertThat(pool.release(e2), is(false));
        assertThat(pool.size(), is(0));
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testReleaseThenRemoveNonEnabledEntry(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(1, strategy);
        Pool<String>.Entry e = pool.reserve(-1);
        assertThat(pool.size(), is(1));
        assertThat(pool.release(e), is(false));
        assertThat(pool.size(), is(1));
        assertThat(pool.remove(e), is(true));
        assertThat(pool.size(), is(0));
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testRemoveNonEnabledEntry(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(1, strategy);
        Pool<String>.Entry e = pool.reserve(-1);
        assertThat(pool.size(), is(1));
        assertThat(pool.remove(e), is(true));
        assertThat(pool.size(), is(0));
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testMultiplexMaxUsageReachedAcquireThenRemove(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(1, strategy);
        pool.setMaxMultiplex(2);
        pool.setMaxUsageCount(3);
        pool.reserve(-1).enable("aaa", false);

        Pool<String>.Entry e0 = pool.acquire();

        Pool<String>.Entry e1 = pool.acquire();
        assertThat(pool.release(e1), is(true));
        Pool<String>.Entry e2 = pool.acquire();
        assertThat(pool.release(e2), is(true));
        assertThat(pool.acquire(), nullValue());

        assertThat(pool.remove(e0), is(true));
        assertThat(pool.size(), is(0));
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testMultiplexMaxUsageReachedAcquireThenReleaseThenRemove(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(1, strategy);
        pool.setMaxMultiplex(2);
        pool.setMaxUsageCount(3);
        pool.reserve(-1).enable("aaa", false);

        Pool<String>.Entry e0 = pool.acquire();

        Pool<String>.Entry e1 = pool.acquire();
        assertThat(pool.release(e1), is(true));
        Pool<String>.Entry e2 = pool.acquire();
        assertThat(pool.release(e2), is(true));
        assertThat(pool.acquire(), nullValue());

        assertThat(pool.release(e0), is(false));
        assertThat(pool.values().stream().findFirst().get().isIdle(), is(true));
        assertThat(pool.values().stream().findFirst().get().isClosed(), is(false));
        assertThat(pool.size(), is(1));
        assertThat(pool.remove(e0), is(true));
        assertThat(pool.size(), is(0));
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testUsageCountAfterReachingMaxMultiplexLimit(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(1, strategy);
        pool.setMaxMultiplex(2);
        pool.setMaxUsageCount(10);
        pool.reserve(-1).enable("aaa", false);

        Pool<String>.Entry e1 = pool.acquire();
        assertThat(e1.getUsageCount(), is(1));
        Pool<String>.Entry e2 = pool.acquire();
        assertThat(e1.getUsageCount(), is(2));
        assertThat(pool.acquire(), nullValue());
        assertThat(e1.getUsageCount(), is(2));
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testConfigLimits(Pool.Strategy<String> strategy)
    {
        assertThrows(IllegalArgumentException.class, () -> new Pool<String>(1, 0).setMaxMultiplex(0));
        assertThrows(IllegalArgumentException.class, () -> new Pool<String>(1, 0).setMaxMultiplex(-1));
        assertThrows(IllegalArgumentException.class, () -> new Pool<String>(1, 0).setMaxUsageCount(0));
    }

    @ParameterizedTest
    @MethodSource(value = "strategy")
    public void testAcquireWithCreator(Pool.Strategy<String> strategy)
    {
        Pool<String> pool = new Pool<>(2, strategy);

        assertThat(pool.size(), is(0));
        assertThat(pool.acquire(e -> null), nullValue());
        assertThat(pool.size(), is(0));

        Pool<String>.Entry e1 = pool.acquire(e -> "e1");
        assertThat(e1.getPooled(), is("e1"));
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getInUseCount(), is(1));

        assertThat(pool.acquire(e -> null), nullValue());
        assertThat(pool.size(), is(1));

        Pool<String>.Entry e2 = pool.acquire(e -> "e2");
        assertThat(e2.getPooled(), is("e2"));
        assertThat(pool.size(), is(2));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getInUseCount(), is(2));

        Pool<String>.Entry e3 = pool.acquire(e -> "e3");
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

        Pool<String>.Entry e4 = pool.acquire(e -> "e4");
        assertThat(e4.getPooled(), is("e2"));
        assertThat(pool.size(), is(2));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getInUseCount(), is(2));

        pool.remove(e1);
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
        Pool<AtomicInteger> pool = new Pool<>(4, new Pool.RoundRobinStrategy<>());

        Pool<AtomicInteger>.Entry e1 = pool.acquire(e -> new AtomicInteger());
        Pool<AtomicInteger>.Entry e2 = pool.acquire(e -> new AtomicInteger());
        Pool<AtomicInteger>.Entry e3 = pool.acquire(e -> new AtomicInteger());
        Pool<AtomicInteger>.Entry e4 = pool.acquire(e -> new AtomicInteger());
        assertNull(pool.acquire(e -> new AtomicInteger()));

        pool.release(e1);
        pool.release(e2);
        pool.release(e3);
        pool.release(e4);

        Pool<AtomicInteger>.Entry last = null;
        for (int i = 0; i < 8; i++)
        {
            Pool<AtomicInteger>.Entry e = pool.acquire();
            if (last != null)
                assertThat(e, not(sameInstance(last)));
            e.getPooled().incrementAndGet();
            pool.release(e);
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
        Pool<AtomicInteger> pool = new Pool<>(4, new Pool.CompositeStrategy<>(new Pool.RandomStrategy<>(), new Pool.LinearSearchStrategy<>()));

        Pool<AtomicInteger>.Entry e1 = pool.acquire(e -> new AtomicInteger());
        Pool<AtomicInteger>.Entry e2 = pool.acquire(e -> new AtomicInteger());
        Pool<AtomicInteger>.Entry e3 = pool.acquire(e -> new AtomicInteger());
        Pool<AtomicInteger>.Entry e4 = pool.acquire(e -> new AtomicInteger());
        assertNull(pool.acquire(e -> new AtomicInteger()));

        pool.release(e1);
        pool.release(e2);
        pool.release(e3);
        pool.release(e4);

        for (int i = 0; i < 400; i++)
        {
            Pool<AtomicInteger>.Entry e = pool.acquire();
            e.getPooled().incrementAndGet();
            pool.release(e);
        }

        assertThat(e1.getPooled().get(), greaterThan(10));
        assertThat(e2.getPooled().get(), greaterThan(10));
        assertThat(e3.getPooled().get(), greaterThan(10));
        assertThat(e4.getPooled().get(), greaterThan(10));
    }
}
