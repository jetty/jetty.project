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

package org.eclipse.jetty.io;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CyclicTimeoutsTest
{
    private Scheduler scheduler;
    private CyclicTimeouts<ConstantExpirable> timeouts;

    @BeforeEach
    public void prepare()
    {
        scheduler = new ScheduledExecutorScheduler();
        LifeCycle.start(scheduler);
    }

    @AfterEach
    public void dispose()
    {
        if (timeouts != null)
            timeouts.destroy();
        LifeCycle.stop(scheduler);
    }

    @Test
    public void testNoExpirationForNonExpiringEntity() throws Exception
    {
        CountDownLatch latch = new CountDownLatch(1);
        timeouts = new CyclicTimeouts<>(scheduler)
        {
            @Override
            protected Iterator<ConstantExpirable> iterator()
            {
                latch.countDown();
                return null;
            }

            @Override
            protected boolean onExpired(ConstantExpirable expirable)
            {
                return false;
            }
        };

        // Schedule an entity that does not expire.
        timeouts.schedule(ConstantExpirable.noExpire());

        Assertions.assertFalse(latch.await(1, TimeUnit.SECONDS));
    }

    @Test
    public void testScheduleZero() throws Exception
    {
        ConstantExpirable entity = ConstantExpirable.ofDelay(0, TimeUnit.SECONDS);
        CountDownLatch iteratorLatch = new CountDownLatch(1);
        CountDownLatch expiredLatch = new CountDownLatch(1);
        timeouts = new CyclicTimeouts<>(scheduler)
        {
            @Override
            protected Iterator<ConstantExpirable> iterator()
            {
                iteratorLatch.countDown();
                return Collections.emptyIterator();
            }

            @Override
            protected boolean onExpired(ConstantExpirable expirable)
            {
                expiredLatch.countDown();
                return false;
            }
        };

        timeouts.schedule(entity);

        Assertions.assertTrue(iteratorLatch.await(1, TimeUnit.SECONDS));
        Assertions.assertFalse(expiredLatch.await(1, TimeUnit.SECONDS));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testIterateAndExpire(boolean remove) throws Exception
    {
        ConstantExpirable zero = ConstantExpirable.ofDelay(0, TimeUnit.SECONDS);
        ConstantExpirable one = ConstantExpirable.ofDelay(1, TimeUnit.SECONDS);
        Collection<ConstantExpirable> collection = new ArrayList<>();
        collection.add(one);
        AtomicInteger iterations = new AtomicInteger();
        CountDownLatch expiredLatch = new CountDownLatch(1);
        timeouts = new CyclicTimeouts<>(scheduler)
        {
            @Override
            protected Iterator<ConstantExpirable> iterator()
            {
                iterations.incrementAndGet();
                return collection.iterator();
            }

            @Override
            protected boolean onExpired(ConstantExpirable expirable)
            {
                assertSame(one, expirable);
                expiredLatch.countDown();
                return remove;
            }
        };

        // Triggers immediate call to iterator(), which
        // returns an entity that expires in 1 second.
        timeouts.schedule(zero);

        // After 1 second there is a second call to
        // iterator(), which returns the now expired
        // entity, which is passed to onExpired().
        assertTrue(expiredLatch.await(2, TimeUnit.SECONDS));

        // Wait for the collection to be processed
        // with the return value of onExpired().
        Thread.sleep(1000);

        // Verify the processing of the return value of onExpired().
        assertEquals(remove ? 0 : 1, collection.size());

        // Wait to see if iterator() is called again (it should not).
        Thread.sleep(1000);
        assertEquals(2, iterations.get());
    }

    @Test
    public void testScheduleOvertake() throws Exception
    {
        ConstantExpirable zero = ConstantExpirable.ofDelay(0, TimeUnit.SECONDS);
        long delayMs = 2000;
        ConstantExpirable two = ConstantExpirable.ofDelay(delayMs, TimeUnit.MILLISECONDS);
        ConstantExpirable overtake = ConstantExpirable.ofDelay(delayMs / 2, TimeUnit.MILLISECONDS);
        Collection<ConstantExpirable> collection = new ArrayList<>();
        collection.add(two);
        CountDownLatch expiredLatch = new CountDownLatch(2);
        List<ConstantExpirable> expired = new ArrayList<>();
        timeouts = new CyclicTimeouts<>(scheduler)
        {
            private final AtomicBoolean overtakeScheduled = new AtomicBoolean();

            @Override
            protected Iterator<ConstantExpirable> iterator()
            {
                return collection.iterator();
            }

            @Override
            protected boolean onExpired(ConstantExpirable expirable)
            {
                expired.add(expirable);
                expiredLatch.countDown();
                return true;
            }

            @Override
            boolean schedule(CyclicTimeout cyclicTimeout, long delay, TimeUnit unit)
            {
                if (delay <= 0)
                    return super.schedule(cyclicTimeout, delay, unit);

                // Simulate that an entity with a shorter timeout
                // overtakes the entity that is currently being scheduled.
                // Only schedule the overtake once.
                if (overtakeScheduled.compareAndSet(false, true))
                {
                    collection.add(overtake);
                    schedule(overtake);
                }
                return super.schedule(cyclicTimeout, delay, unit);
            }
        };

        // Trigger the initial call to iterator().
        timeouts.schedule(zero);

        // Make sure that the overtake entity expires properly.
        assertTrue(expiredLatch.await(2 * delayMs, TimeUnit.MILLISECONDS));

        // Make sure all entities expired properly.
        assertSame(overtake, expired.get(0));
        assertSame(two, expired.get(1));
    }

    private static class ConstantExpirable implements CyclicTimeouts.Expirable
    {
        private static ConstantExpirable noExpire()
        {
            return new ConstantExpirable();
        }

        private static ConstantExpirable ofDelay(long delay, TimeUnit unit)
        {
            return new ConstantExpirable(delay, unit);
        }

        private final long expireNanos;
        private final String asString;

        private ConstantExpirable()
        {
            this.expireNanos = Long.MAX_VALUE;
            this.asString = "noexp";
        }

        public ConstantExpirable(long delay, TimeUnit unit)
        {
            this.expireNanos = System.nanoTime() + unit.toNanos(delay);
            this.asString = String.valueOf(unit.toMillis(delay));
        }

        @Override
        public long getExpireNanoTime()
        {
            return expireNanos;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[%sms]", getClass().getSimpleName(), hashCode(), asString);
        }
    }
}
