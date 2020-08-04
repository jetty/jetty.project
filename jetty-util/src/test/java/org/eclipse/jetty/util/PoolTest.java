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
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PoolTest
{
    @Test
    public void testAcquireRelease()
    {
        Pool<String> pool = new Pool<>(1,0);
        pool.reserve(-1).enable("aaa");

        assertThat(pool.values().stream().findFirst().get().isIdle(), is(true));
        Pool<String>.Entry e1 = pool.acquire();
        assertThat(e1.getPooled(), equalTo("aaa"));
        assertThat(pool.values().stream().findFirst().get().isIdle(), is(false));
        assertThat(pool.acquire(), nullValue());
        assertThat(pool.release(e1), is(true));
        assertThat(pool.values().stream().findFirst().get().isIdle(), is(true));
        assertThrows(IllegalStateException.class, () -> pool.release(e1));
        Pool<String>.Entry e2 = pool.acquire();
        assertThat(e2.getPooled(), equalTo("aaa"));
        assertThat(pool.release(e2), is(true));
        assertThrows(NullPointerException.class, () -> pool.release(null));
    }

    @Test
    public void testRemoveBeforeRelease()
    {
        Pool<String> pool = new Pool<>(1,0);
        pool.reserve(-1).enable("aaa");

        Pool<String>.Entry e1 = pool.acquire();
        assertThat(pool.remove(e1), is(true));
        assertThat(pool.remove(e1), is(false));
        assertThat(pool.release(e1), is(false));
    }

    @Test
    public void testCloseBeforeRelease()
    {
        Pool<String> pool = new Pool<>(1,0);
        pool.reserve(-1).enable("aaa");

        Pool<String>.Entry e1 = pool.acquire();
        assertThat(pool.size(), is(1));
        pool.close();
        assertThat(pool.size(), is(0));
        assertThat(pool.release(e1), is(false));
    }

    @Test
    public void testMaxPoolSize()
    {
        Pool<String> pool = new Pool<>(1, 0);
        assertThat(pool.size(), is(0));
        assertThat(pool.reserve(-1), notNullValue());
        assertThat(pool.size(), is(1));
        assertThat(pool.reserve(-1), nullValue());
        assertThat(pool.size(), is(1));
    }

    @Test
    public void testReserve()
    {
        Pool<String> pool = new Pool<>(2, 0);
        Pool<String>.Reservation reservation = pool.reserve(-1);
        assertThat(pool.size(), is(1));
        assertThat(pool.acquire(), nullValue());
        assertThat(reservation.getEntry().isClosed(), is(true));

        assertThrows(NullPointerException.class, () -> reservation.enable(null));
        assertThat(pool.acquire(), nullValue());
        assertThat(reservation.getEntry().isClosed(), is(true));

        reservation.enable("aaa");
        assertThat(reservation.getEntry().isClosed(), is(false));
        assertThat(pool.acquire().getPooled(), notNullValue());

        assertThrows(IllegalStateException.class, () -> reservation.enable("bbb"));

        Pool<String>.Reservation r2 = pool.reserve(-1);
        assertThat(pool.size(), is(2));
        r2.remove();
        assertThat(pool.size(), is(1));

        pool.reserve(-1);
        assertThat(pool.size(), is(2));
        pool.close();
        assertThat(pool.size(), is(0));
        assertThat(pool.reserve(-1), nullValue());
        assertThat(reservation.getEntry().isClosed(), is(true));
    }

    @Test
    public void testReserveMaxPending()
    {
        Pool<String> pool = new Pool<>(2, 0);
        assertThat(pool.reserve(0), nullValue());
        assertThat(pool.reserve(1), notNullValue());
        assertThat(pool.reserve(1), nullValue());
        assertThat(pool.reserve(2), notNullValue());
        assertThat(pool.reserve(2), nullValue());
        assertThat(pool.reserve(3), nullValue());
        assertThat(pool.reserve(-1), nullValue());
    }

    @Test
    public void testReserveNegativeMaxPending()
    {
        Pool<String> pool = new Pool<>(2, 0);
        assertThat(pool.reserve(-1), notNullValue());
        assertThat(pool.reserve(-1), notNullValue());
        assertThat(pool.reserve(-1), nullValue());
    }

    @Test
    public void testClose()
    {
        Pool<String> pool = new Pool<>(1, 0);
        pool.reserve(-1).enable("aaa");
        assertThat(pool.isClosed(), is(false));
        pool.close();
        pool.close();

        assertThat(pool.isClosed(), is(true));
        assertThat(pool.size(), is(0));
        assertThat(pool.acquire(), nullValue());
        assertThat(pool.reserve(-1), nullValue());
    }

    @Test
    public void testClosingCloseable()
    {
        AtomicBoolean closed = new AtomicBoolean();
        Pool<Closeable> pool = new Pool<>(1,0);
        Closeable pooled = () -> closed.set(true);
        pool.reserve(-1).enable(pooled);
        assertThat(closed.get(), is(false));
        pool.close();
        assertThat(closed.get(), is(true));
    }

    @Test
    public void testRemove()
    {
        Pool<String> pool = new Pool<>(1, 0);
        pool.reserve(-1).enable("aaa");

        Pool<String>.Entry e1 = pool.acquire();
        assertThat(pool.remove(e1), is(true));
        assertThat(pool.remove(e1), is(false));
        assertThat(pool.release(e1), is(false));
        assertThat(pool.acquire(), nullValue());
        assertThrows(NullPointerException.class, () -> pool.remove(null));
    }

    @Test
    public void testValuesSize()
    {
        Pool<String> pool = new Pool<>(2, 0);

        assertThat(pool.size(), is(0));
        assertThat(pool.values().isEmpty(), is(true));
        pool.reserve(-1).enable("aaa");
        pool.reserve(-1).enable("bbb");
        assertThat(pool.values().stream().map(Pool.Entry::getPooled).collect(toList()), equalTo(Arrays.asList("aaa", "bbb")));
        assertThat(pool.size(), is(2));
    }

    @Test
    public void testValuesContainsAcquiredEntries()
    {
        Pool<String> pool = new Pool<>(2, 0);

        pool.reserve(-1).enable("aaa");
        pool.reserve(-1).enable("bbb");
        assertThat(pool.acquire(), notNullValue());
        assertThat(pool.acquire(), notNullValue());
        assertThat(pool.acquire(), nullValue());
        assertThat(pool.values().isEmpty(), is(false));
    }

    @Test
    public void testAcquireAt()
    {
        Pool<String> pool = new Pool<>(2, 0);

        pool.reserve(-1).enable("aaa");
        pool.reserve(-1).enable("bbb");

        assertThat(pool.acquireAt(2), nullValue());
        assertThat(pool.acquireAt(0), notNullValue());
        assertThat(pool.acquireAt(0), nullValue());
        assertThat(pool.acquireAt(1), notNullValue());
        assertThat(pool.acquireAt(1), nullValue());
    }

    @Test
    public void testMaxUsageCount()
    {
        Pool<String> pool = new Pool<>(1, 0);
        pool.setMaxUsageCount(3);
        pool.reserve(-1).enable("aaa");

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

    @Test
    public void testMaxMultiplex()
    {
        Pool<String> pool = new Pool<>(2, 0);
        pool.setMaxMultiplex(3);
        pool.reserve(-1).enable("aaa");
        pool.reserve(-1).enable("bbb");

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

    @Test
    public void testRemoveMultiplexed()
    {
        Pool<String> pool = new Pool<>(1, 0);
        pool.setMaxMultiplex(2);
        pool.reserve(-1).enable("aaa");

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

    @Test
    public void testMultiplexRemoveThenAcquireThenReleaseRemove()
    {
        Pool<String> pool = new Pool<>(1, 0);
        pool.setMaxMultiplex(2);
        pool.reserve(-1).enable("aaa");

        Pool<String>.Entry e1 = pool.acquire();
        Pool<String>.Entry e2 = pool.acquire();

        assertThat(pool.remove(e1), is(false));
        assertThat(e1.isClosed(), is(true));
        assertThat(pool.acquire(), nullValue());
        assertThat(pool.release(e2), is(false));
        assertThat(pool.remove(e2), is(true));
    }

    @Test
    public void testNonMultiplexRemoveAfterAcquire()
    {
        Pool<String> pool = new Pool<>(1, 0);
        pool.setMaxMultiplex(2);
        pool.reserve(-1).enable("aaa");

        Pool<String>.Entry e1 = pool.acquire();
        assertThat(pool.remove(e1), is(true));
        assertThat(pool.size(), is(0));
    }

    @Test
    public void testMultiplexRemoveAfterAcquire()
    {
        Pool<String> pool = new Pool<>(1, 0);
        pool.setMaxMultiplex(2);
        pool.reserve(-1).enable("aaa");

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

    @Test
    public void testReleaseThenRemoveNonEnabledEntry()
    {
        Pool<String> pool = new Pool<>(1, 0);
        Pool<String>.Reservation r = pool.reserve(-1);
        assertThat(pool.size(), is(1));
        assertThat(pool.release(r.getEntry()), is(false));
        assertThat(pool.size(), is(1));
        assertThat(pool.remove(r.getEntry()), is(true));
        assertThat(pool.size(), is(0));
    }

    @Test
    public void testRemoveNonEnabledEntry()
    {
        Pool<String> pool = new Pool<>(1, 0);
        Pool<String>.Reservation r = pool.reserve(-1);
        assertThat(pool.size(), is(1));
        assertThat(pool.getPendingConnectionCount(), is(1));
        assertThat(pool.remove(r.getEntry()), is(true));
        assertThat(pool.size(), is(0));
        assertThat(pool.getPendingConnectionCount(), is(0));
    }

    @Test
    public void testMultiplexMaxUsageReachedAcquireThenRemove()
    {
        Pool<String> pool = new Pool<>(1, 0);
        pool.setMaxMultiplex(2);
        pool.setMaxUsageCount(3);
        pool.reserve(-1).enable("aaa");

        Pool<String>.Entry e0 = pool.acquire();

        Pool<String>.Entry e1 = pool.acquire();
        assertThat(pool.release(e1), is(true));
        Pool<String>.Entry e2 = pool.acquire();
        assertThat(pool.release(e2), is(true));
        assertThat(pool.acquire(), nullValue());

        assertThat(pool.remove(e0), is(true));
        assertThat(pool.size(), is(0));
    }
    
    @Test
    public void testMultiplexMaxUsageReachedAcquireThenReleaseThenRemove()
    {
        Pool<String> pool = new Pool<>(1, 0);
        pool.setMaxMultiplex(2);
        pool.setMaxUsageCount(3);
        pool.reserve(-1).enable("aaa");

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

    @Test
    public void testUsageCountAfterReachingMaxMultiplexLimit()
    {
        Pool<String> pool = new Pool<>(1, 0);
        pool.setMaxMultiplex(2);
        pool.setMaxUsageCount(10);
        pool.reserve(-1).enable("aaa");

        Pool<String>.Entry e1 = pool.acquire();
        assertThat(e1.getUsageCount(), is(1));
        Pool<String>.Entry e2 = pool.acquire();
        assertThat(e1.getUsageCount(), is(2));
        assertThat(pool.acquire(), nullValue());
        assertThat(e1.getUsageCount(), is(2));
    }

    @Test
    public void testConfigLimits()
    {
        assertThrows(IllegalArgumentException.class, () -> new Pool<String>(1, 0).setMaxMultiplex(0));
        assertThrows(IllegalArgumentException.class, () -> new Pool<String>(1, 0).setMaxMultiplex(-1));
        assertThrows(IllegalArgumentException.class, () -> new Pool<String>(1, 0).setMaxUsageCount(0));
    }
}
