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

package org.eclipse.jetty.io.internal;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.eclipse.jetty.util.Pool;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class QueuedPoolTest
{
    @Test
    public void testAcquireRelease()
    {
        Pool<String> pool = new QueuedPool<>(1);
        pool.reserve().enable("aaa", false);
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        Pool.Entry<String> e1 = pool.acquire();
        assertThat(e1.getPooled(), equalTo("aaa"));
        assertThat(pool.size(), is(0));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(0));
        assertThat(pool.getInUseCount(), is(0));

        assertNull(pool.acquire());

        assertThat(e1.release(), is(true));
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        assertThat(e1.release(), is(false));

        Pool.Entry<String> e2 = pool.acquire();
        assertThat(e2.getPooled(), equalTo("aaa"));
        assertThat(pool.size(), is(0));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(0));
        assertThat(pool.getInUseCount(), is(0));

        assertThat(e2.release(), is(true));
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        assertThat(e2.release(), is(false));
    }

    @Test
    public void testReleaseBeforeRemove()
    {
        Pool<String> pool = new QueuedPool<>(1);
        pool.reserve().enable("aaa", false);

        Pool.Entry<String> e1 = pool.acquire();

        assertThat(e1.release(), is(true));
        assertThat(e1.remove(), is(true));
        assertThat(e1.remove(), is(false));
        assertThat(e1.getPooled(), nullValue());
    }

    @Test
    public void testRemoveBeforeRelease()
    {
        Pool<String> pool = new QueuedPool<>(1);
        pool.reserve().enable("aaa", false);

        Pool.Entry<String> e1 = pool.acquire();
        assertThat(e1.remove(), is(true));
        assertThat(e1.remove(), is(false));
        assertThat(e1.release(), is(false));
        assertThat(e1.getPooled(), nullValue());
    }

    @Test
    public void testTerminateBeforeRelease()
    {
        Pool<String> pool = new QueuedPool<>(1);
        pool.reserve().enable("aaa", false);
        assertThat(pool.size(), is(1));

        Pool.Entry<String> e1 = pool.acquire();
        assertThat(pool.size(), is(0));

        Collection<Pool.Entry<String>> entries = pool.terminate();
        assertThat(pool.size(), is(0));
        assertThat(entries.size(), is(0));

        assertThat(e1.release(), is(false));
        assertThat(e1.remove(), is(true));
        assertThat(e1.remove(), is(false));
    }

    @Test
    public void testMaxPoolSize()
    {
        Pool<String> pool = new QueuedPool<>(1);
        assertThat(pool.size(), is(0));
        assertThat(pool.reserve().enable("aaa", false), is(true));
        assertThat(pool.size(), is(1));
        assertThat(pool.reserve(), nullValue());
        assertThat(pool.size(), is(1));
    }

    @Test
    public void testReserve()
    {
        Pool<String> pool = new QueuedPool<>(2);

        // Reserve an entry
        Pool.Entry<String> e1 = pool.reserve();
        assertThat(pool.size(), is(0));
        assertThat(pool.getReservedCount(), is(0));
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
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
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
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        // enable and acquire the entry
        e3.enable("bbb", true);
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getIdleCount(), is(1));
        assertThat(pool.getInUseCount(), is(0));

        // can't reenable
        assertThrows(IllegalStateException.class, () -> e3.enable("xxx", false));

        // Can't enable acquired entry
        Pool.Entry<String> e = pool.acquire();
        assertThrows(IllegalStateException.class, () -> e.enable("xxx", false));
    }

    @Test
    public void testReserveIsAccountedForInMaxEntries()
    {
        Pool<String> pool = new QueuedPool<>(2);
        assertThat(pool.reserve(), notNullValue());
        assertThat(pool.reserve(), notNullValue());
        assertThat(pool.reserve(), notNullValue());
    }

    @Test
    public void testReserveAndRemove()
    {
        Pool<String> pool = new QueuedPool<>(1);
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

    @Test
    public void testTerminate()
    {
        Pool<String> pool = new QueuedPool<>(1);
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

    @Test
    public void testReserveAndTerminate()
    {
        Pool<String> pool = new QueuedPool<>(1);
        Pool.Entry<String> entry = pool.reserve();
        assertThat(entry.enable("aaa", false), is(true));

        Collection<Pool.Entry<String>> entries = pool.terminate();
        assertThat(entries.size(), is(1));
        assertThat(entries.iterator().next(), sameInstance(entry));

        assertThat(entry.enable("aaa", false), is(false));
        assertThat(entry.enable("bbb", true), is(false));

        assertThat(entry.release(), is(false));
        assertThat(entry.remove(), is(true));
        assertThat(entry.remove(), is(false));
    }

    @Test
    public void testRemove()
    {
        Pool<String> pool = new QueuedPool<>(1);
        pool.reserve().enable("aaa", false);

        Pool.Entry<String> e1 = pool.acquire();
        assertThat(e1.remove(), is(true));
        assertThat(e1.remove(), is(false));
        assertThat(e1.release(), is(false));
        assertThat(pool.acquire(), nullValue());
    }

    @Test
    public void testValuesSize()
    {
        Pool<String> pool = new QueuedPool<>(2);

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

    @Test
    public void testValuesContainsAcquiredEntries()
    {
        Pool<String> pool = new QueuedPool<>(2);

        pool.reserve().enable("aaa", false);
        pool.reserve().enable("bbb", false);
        assertThat(pool.acquire(), notNullValue());
        assertThat(pool.acquire(), notNullValue());
        assertThat(pool.acquire(), nullValue());
        assertThat(pool.stream().count(), not(is(0)));
    }

    @Test
    public void testRemoveAfterAcquire()
    {
        Pool<String> pool = new QueuedPool<>(1);
        pool.reserve().enable("aaa", false);

        Pool.Entry<String> e1 = pool.acquire();
        assertThat(e1.remove(), is(true));
        assertThat(pool.size(), is(0));
    }

    @Test
    public void testReleaseThenRemoveNonEnabledEntry()
    {
        Pool<String> pool = new QueuedPool<>(1);
        Pool.Entry<String> e = pool.reserve();
        assertThat(pool.size(), is(0));
        assertThat(e.release(), is(false));
        assertThat(pool.size(), is(0));
        assertThat(e.remove(), is(true));
        assertThat(pool.size(), is(0));
    }

    @Test
    public void testNonEnabledEntryIsNotQueued()
    {
        Pool<String> pool = new QueuedPool<>(1);
        Pool.Entry<String> e = pool.reserve();
        assertThat(pool.size(), is(0));
    }

    @Test
    public void testAcquireWithCreator()
    {
        Pool<String> pool = new QueuedPool<>(2);

        assertThat(pool.size(), is(0));
        assertThat(pool.acquire(e -> null), nullValue());
        assertThat(pool.size(), is(0));

        Pool.Entry<String> e1 = pool.acquire(e -> "e1");
        assertThat(e1.getPooled(), is("e1"));
        assertThat(pool.size(), is(0));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getInUseCount(), is(0));

        assertThat(pool.acquire(e -> null), nullValue());
        assertThat(pool.size(), is(0));

        Pool.Entry<String> e2 = pool.acquire(e -> "e2");
        assertThat(e2.getPooled(), is("e2"));
        assertThat(pool.size(), is(0));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getInUseCount(), is(0));

        assertThat(e2.release(), is(true));
        assertThat(pool.size(), is(1));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getInUseCount(), is(0));

        Pool.Entry<String> e4 = pool.acquire(e -> "e4");
        assertThat(e4.getPooled(), is("e2"));
        assertThat(pool.size(), is(0));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getInUseCount(), is(0));
        assertThat(e4, sameInstance(e2));
        assertThat(e4.release(), is(true));

        assertThat(e1.release(), is(true));
        assertThat(pool.size(), is(2));
        assertThat(pool.getReservedCount(), is(0));
        assertThat(pool.getInUseCount(), is(0));
    }
}
