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

package org.eclipse.jetty.util;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PoolTest
{
    public static Stream<Object[]> cacheSize()
    {
        List<Object[]> data = new ArrayList<>();
        data.add(new Object[]{0});
        data.add(new Object[]{1});
        data.add(new Object[]{2});
        return data.stream();
    }

    @ParameterizedTest
    @MethodSource(value = "cacheSize")
    public void testAcquireRelease(int cacheSize)
    {
        Pool<String> pool = new Pool<>(1,cacheSize);
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
    @MethodSource(value = "cacheSize")
    public void testRemoveBeforeRelease(int cacheSize)
    {
        Pool<String> pool = new Pool<>(1, cacheSize);
        pool.reserve(-1).enable("aaa", false);

        Pool<String>.Entry e1 = pool.acquire();
        assertThat(pool.remove(e1), is(true));
        assertThat(pool.remove(e1), is(false));
        assertThat(pool.release(e1), is(false));
    }

    @ParameterizedTest
    @MethodSource(value = "cacheSize")
    public void testCloseBeforeRelease(int cacheSize)
    {
        Pool<String> pool = new Pool<>(1, cacheSize);
        pool.reserve(-1).enable("aaa", false);

        Pool<String>.Entry e1 = pool.acquire();
        assertThat(pool.size(), is(1));
        pool.close();
        assertThat(pool.size(), is(0));
        assertThat(pool.release(e1), is(false));
    }

    @ParameterizedTest
    @MethodSource(value = "cacheSize")
    public void testMaxPoolSize(int cacheSize)
    {
        Pool<String> pool = new Pool<>(1, cacheSize);
        assertThat(pool.size(), is(0));
        assertThat(pool.reserve(-1), notNullValue());
        assertThat(pool.size(), is(1));
        assertThat(pool.reserve(-1), nullValue());
        assertThat(pool.size(), is(1));
    }

    @ParameterizedTest
    @MethodSource(value = "cacheSize")
    public void testReserve(int cacheSize)
    {
        Pool<String> pool = new Pool<>(2, cacheSize);

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
    @MethodSource(value = "cacheSize")
    public void testReserveMaxPending(int cacheSize)
    {
        Pool<String> pool = new Pool<>(2, cacheSize);
        assertThat(pool.reserve(0), nullValue());
        assertThat(pool.reserve(1), notNullValue());
        assertThat(pool.reserve(1), nullValue());
        assertThat(pool.reserve(2), notNullValue());
        assertThat(pool.reserve(2), nullValue());
        assertThat(pool.reserve(3), nullValue());
        assertThat(pool.reserve(-1), nullValue());
    }

    @ParameterizedTest
    @MethodSource(value = "cacheSize")
    public void testReserveNegativeMaxPending(int cacheSize)
    {
        Pool<String> pool = new Pool<>(2, cacheSize);
        assertThat(pool.reserve(-1), notNullValue());
        assertThat(pool.reserve(-1), notNullValue());
        assertThat(pool.reserve(-1), nullValue());
    }

    @ParameterizedTest
    @MethodSource(value = "cacheSize")
    public void testClose(int cacheSize)
    {
        Pool<String> pool = new Pool<>(1, cacheSize);
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
    @MethodSource(value = "cacheSize")
    public void testClosingCloseable(int cacheSize)
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
    @MethodSource(value = "cacheSize")
    public void testRemove(int cacheSize)
    {
        Pool<String> pool = new Pool<>(1, cacheSize);
        pool.reserve(-1).enable("aaa", false);

        Pool<String>.Entry e1 = pool.acquire();
        assertThat(pool.remove(e1), is(true));
        assertThat(pool.remove(e1), is(false));
        assertThat(pool.release(e1), is(false));
        assertThat(pool.acquire(), nullValue());
        assertThrows(NullPointerException.class, () -> pool.remove(null));
    }

    @ParameterizedTest
    @MethodSource(value = "cacheSize")
    public void testValuesSize(int cacheSize)
    {
        Pool<String> pool = new Pool<>(2, cacheSize);

        assertThat(pool.size(), is(0));
        assertThat(pool.values().isEmpty(), is(true));
        pool.reserve(-1).enable("aaa", false);
        pool.reserve(-1).enable("bbb", false);
        assertThat(pool.values().stream().map(Pool.Entry::getPooled).collect(toList()), equalTo(Arrays.asList("aaa", "bbb")));
        assertThat(pool.size(), is(2));
    }

    @ParameterizedTest
    @MethodSource(value = "cacheSize")
    public void testValuesContainsAcquiredEntries(int cacheSize)
    {
        Pool<String> pool = new Pool<>(2, cacheSize);

        pool.reserve(-1).enable("aaa", false);
        pool.reserve(-1).enable("bbb", false);
        assertThat(pool.acquire(), notNullValue());
        assertThat(pool.acquire(), notNullValue());
        assertThat(pool.acquire(), nullValue());
        assertThat(pool.values().isEmpty(), is(false));
    }

    @ParameterizedTest
    @MethodSource(value = "cacheSize")
    public void testAcquireAt(int cacheSize)
    {
        Pool<String> pool = new Pool<>(2, cacheSize);

        pool.reserve(-1).enable("aaa", false);
        pool.reserve(-1).enable("bbb", false);

        assertThat(pool.acquireAt(2), nullValue());
        assertThat(pool.acquireAt(0), notNullValue());
        assertThat(pool.acquireAt(0), nullValue());
        assertThat(pool.acquireAt(1), notNullValue());
        assertThat(pool.acquireAt(1), nullValue());
    }

    @ParameterizedTest
    @MethodSource(value = "cacheSize")
    public void testMaxUsageCount(int cacheSize)
    {
        Pool<String> pool = new Pool<>(1, cacheSize);
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
    @MethodSource(value = "cacheSize")
    public void testMaxMultiplex(int cacheSize)
    {
        Pool<String> pool = new Pool<>(2, cacheSize);
        pool.setMaxMultiplex(3);
        pool.reserve(-1).enable("aaa", false);
        pool.reserve(-1).enable("bbb", false);

        Pool<String>.Entry e1 = pool.acquire();
        Pool<String>.Entry e2 = pool.acquire();
        Pool<String>.Entry e3 = pool.acquire();
        Pool<String>.Entry e4 = pool.acquire();
        assertThat(e1.getPooled(), equalTo("aaa"));
        assertThat(e1, sameInstance(e2));
        assertThat(e1, sameInstance(e3));
        assertThat(e4.getPooled(), equalTo("bbb"));
        assertThat(pool.release(e1), is(true));
        Pool<String>.Entry e5 = pool.acquire();
        assertThat(e2, sameInstance(e5));
        Pool<String>.Entry e6 = pool.acquire();
        assertThat(e4, sameInstance(e6));
    }

    @ParameterizedTest
    @MethodSource(value = "cacheSize")
    public void testRemoveMultiplexed(int cacheSize)
    {
        Pool<String> pool = new Pool<>(1, cacheSize);
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
    @MethodSource(value = "cacheSize")
    public void testMultiplexRemoveThenAcquireThenReleaseRemove(int cacheSize)
    {
        Pool<String> pool = new Pool<>(1, cacheSize);
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
    @MethodSource(value = "cacheSize")
    public void testNonMultiplexRemoveAfterAcquire(int cacheSize)
    {
        Pool<String> pool = new Pool<>(1, cacheSize);
        pool.setMaxMultiplex(2);
        pool.reserve(-1).enable("aaa", false);

        Pool<String>.Entry e1 = pool.acquire();
        assertThat(pool.remove(e1), is(true));
        assertThat(pool.size(), is(0));
    }

    @ParameterizedTest
    @MethodSource(value = "cacheSize")
    public void testMultiplexRemoveAfterAcquire(int cacheSize)
    {
        Pool<String> pool = new Pool<>(1, cacheSize);
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
    @MethodSource(value = "cacheSize")
    public void testReleaseThenRemoveNonEnabledEntry(int cacheSize)
    {
        Pool<String> pool = new Pool<>(1, cacheSize);
        Pool<String>.Entry e = pool.reserve(-1);
        assertThat(pool.size(), is(1));
        assertThat(pool.release(e), is(false));
        assertThat(pool.size(), is(1));
        assertThat(pool.remove(e), is(true));
        assertThat(pool.size(), is(0));
    }

    @ParameterizedTest
    @MethodSource(value = "cacheSize")
    public void testRemoveNonEnabledEntry(int cacheSize)
    {
        Pool<String> pool = new Pool<>(1, cacheSize);
        Pool<String>.Entry e = pool.reserve(-1);
        assertThat(pool.size(), is(1));
        assertThat(pool.remove(e), is(true));
        assertThat(pool.size(), is(0));
    }

    @ParameterizedTest
    @MethodSource(value = "cacheSize")
    public void testMultiplexMaxUsageReachedAcquireThenRemove(int cacheSize)
    {
        Pool<String> pool = new Pool<>(1, cacheSize);
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
    @MethodSource(value = "cacheSize")
    public void testMultiplexMaxUsageReachedAcquireThenReleaseThenRemove(int cacheSize)
    {
        Pool<String> pool = new Pool<>(1, cacheSize);
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
    @MethodSource(value = "cacheSize")
    public void testUsageCountAfterReachingMaxMultiplexLimit(int cacheSize)
    {
        Pool<String> pool = new Pool<>(1, cacheSize);
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
    @MethodSource(value = "cacheSize")
    public void testConfigLimits(int cacheSize)
    {
        assertThrows(IllegalArgumentException.class, () -> new Pool<String>(1, 0).setMaxMultiplex(0));
        assertThrows(IllegalArgumentException.class, () -> new Pool<String>(1, 0).setMaxMultiplex(-1));
        assertThrows(IllegalArgumentException.class, () -> new Pool<String>(1, 0).setMaxUsageCount(0));
    }

    @ParameterizedTest
    @MethodSource(value = "cacheSize")
    public void testAcquireWithCreator(int cacheSize)
    {
        Pool<String> pool = new Pool<>(2, cacheSize);

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
}
