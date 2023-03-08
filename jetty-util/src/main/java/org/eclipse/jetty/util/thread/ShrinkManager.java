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

package org.eclipse.jetty.util.thread;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.NanoTime;

/**
 * <p>Encapsulates logic for shrinking {@link QueuedThreadPool}. Tracks pool-level idle baseline
 * timestamps as a deque. Upon idle, threads push to the head of the deque by calling {@link #onIdle()},
 * and when an idle thread becomes non-idle (i.e., runs a job), it pops from the head of the deque
 * by calling {@link #onBusy()}.
 *
 * <p>Shrinking proceeds by polling the <i>tail</i> of the deque via {@link #pollIdleShrink(long, long)};
 * the pool thus determines eligibility to shrink based on the longest-standing idle capacity at the pool
 * level.
 *
 * <p>The repeated lifecycle methods {@link #onIdle()} and {@link #onBusy()} return <code>true</code>
 * and <code>false</code> (respectively) as a convenience, indicating whether the calling thread is
 * registered with this {@link ShrinkManager} following the associated method call. It is the
 * responsibility of the calling thread to ensure (e.g., via conditional call to {@link #prune()}
 * in a <code>finally</code> block) that any outstanding entries in this {@link ShrinkManager} are
 * removed if the calling thread exits unexpectedly (i.e., throws an exception).
 */
public class ShrinkManager
{
    private final int mask;
    private final long[] timestamps;
    private final AtomicInteger head = new AtomicInteger();
    private final AtomicInteger tail = new AtomicInteger();
    private final AtomicLong lastShrink = new AtomicLong();

    /**
     * This is the number of times that CaS will retry. Non-configurable default is to never retry.
     * This variable only exists to be set in tests to achieve more precise/reliable behavior, and
     * even at that it should only make a difference for the case where `idleTimeoutDecay > 1`, and
     * only when there are a handful of threads -- &gt; 1 (enough to generate contention), but not
     * so many as that another thread would simply pick up the missed CaS "reservation".
     */
    static int RETRY_LIMIT = 0;

    public ShrinkManager(int maxSize)
    {
        // We make size a power of two for easy mask/circular operation.
        // NOTE: it's perfectly fine for `head`/`tail` to overflow/wrap around.
        int size = Integer.highestOneBit(maxSize + 1) << 1;
        this.mask = size - 1;
        this.timestamps = new long[size];
    }

    /**
     * Called by a thread that is newly idle, either because it just completed a job, or because
     * it has just been created. Returns <code>true</code>, indicating that an entry has been added
     * to this {@link ShrinkManager} for the calling thread.
     */
    public boolean onIdle()
    {
        timestamps[head.getAndIncrement() & mask] = NanoTime.now();
        return true;
    }

    /**
     * Called by a thread just before it performs work. Returns <code>false</code>, indicating that
     * an entry (corresponding to the calling thread) has been removed from this {@link ShrinkManager}.
     */
    public boolean onBusy()
    {
        head.getAndDecrement();
        return false;
    }

    /**
     * Check to see if this pool has idle capacity and is eligible to shrink. If this method returns
     * <code>false</code>, it has no side-effects. If this method returns <code>true</code>, the
     * {@link ShrinkManager} has been updated to record the removal of the thread, and it is the
     * responsibility of the caller to ensure that exactly one corresponding thread (the calling
     * thread) exits, <i>without</i> calling {@link #prune()}.
     *
     * @param itNanos time (in nanos) that pool capacity must be idle in order to be eligible to shrink
     * @param siNanos minimum time (in nanos) between the removal of threads from the pool
     * @return <code>true</code> if the pool should shrink, otherwise <code>false</code>.
     */
    public boolean pollIdleShrink(long itNanos, long siNanos)
    {
        long now = NanoTime.now();
        final long idleBaseline = timestamps[tail.getAndIncrement() & mask];
        if (NanoTime.elapsed(idleBaseline, now) > itNanos)
        {
            long last = lastShrink.get();
            int retries = 0;
            while (NanoTime.elapsed(last, now) > siNanos)
            {
                // shrinkInterval is satisfied
                if (lastShrink.compareAndSet(last, Math.max(last, idleBaseline) + siNanos))
                {
                    // NOTE: attempted CaS may fail, _very_ infrequently. If it does, that's fine.
                    // This is a "best effort" approach to shrinking, and even if our CaS fails,
                    // the missed "shrink reservation" will very likely simply be picked up by
                    // another thread.
                    return true;
                }
                else if (++retries > RETRY_LIMIT)
                {
                    break;
                }
                last = lastShrink.get();
            }
        }
        tail.getAndDecrement();
        return false;
    }

    /**
     * Must be called by any thread with an outstanding entry in this {@link ShrinkManager} that exits for any
     * reason <i>other than</i> {@link #pollIdleShrink(long, long)} returning <code>true</code>. This method will
     * normally not be called except (conditionally) in a <code>finally</code> block if a registered thread exits
     * unexpectedly with an exception.
     */
    public void prune()
    {
        tail.getAndIncrement();
    }

    /**
     * Initializes the baseline timestamp against which idle timestamps are compared to determine eligibility for
     * shrinkage. This should be called to prevent premature idling of threads created early in the life of a pool.
     */
    public void init()
    {
        lastShrink.set(NanoTime.now());
    }
}
