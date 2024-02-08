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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

import org.eclipse.jetty.io.internal.CompoundPool;
import org.eclipse.jetty.io.internal.QueuedPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ConcurrentPool;
import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link RetainableByteBuffer} pool where RetainableByteBuffers are held in {@link Pool}s that are
 * held in array elements.</p>
 * <p>Given a capacity {@code factor} of 1024, the first array element holds a Pool of RetainableByteBuffers
 * each of capacity 1024, the second array element holds a Pool of RetainableByteBuffers each of capacity
 * 2048, and so on with capacities 3072, 4096, 5120, etc.</p>
 * <p>The {@code maxHeapMemory} and {@code maxDirectMemory} default heuristic is to use {@link Runtime#maxMemory()}
 * divided by 8.</p>
 */
@ManagedObject
public class ArrayByteBufferPool implements ByteBufferPool, Dumpable
{
    private static final Logger LOG = LoggerFactory.getLogger(ArrayByteBufferPool.class);
    static final int DEFAULT_FACTOR = 4096;
    static final int DEFAULT_MAX_CAPACITY_BY_FACTOR = 16;

    private final RetainedBucket[] _direct;
    private final RetainedBucket[] _indirect;
    private final int _minCapacity;
    private final int _maxCapacity;
    private final long _maxHeapMemory;
    private final long _maxDirectMemory;
    private final IntUnaryOperator _bucketIndexFor;

    /**
     * Creates a new ArrayByteBufferPool with a default configuration.
     * Both {@code maxHeapMemory} and {@code maxDirectMemory} default to 0 to use default heuristic.
     */
    public ArrayByteBufferPool()
    {
        this(0, -1, -1);
    }

    /**
     * Creates a new ArrayByteBufferPool with the given configuration.
     * Both {@code maxHeapMemory} and {@code maxDirectMemory} default to 0 to use default heuristic.
     *
     * @param minCapacity the minimum ByteBuffer capacity
     * @param factor the capacity factor
     * @param maxCapacity the maximum ByteBuffer capacity
     */
    public ArrayByteBufferPool(int minCapacity, int factor, int maxCapacity)
    {
        this(minCapacity, factor, maxCapacity, Integer.MAX_VALUE);
    }

    /**
     * Creates a new ArrayByteBufferPool with the given configuration.
     * Both {@code maxHeapMemory} and {@code maxDirectMemory} default to 0 to use default heuristic.
     *
     * @param minCapacity the minimum ByteBuffer capacity
     * @param factor the capacity factor
     * @param maxCapacity the maximum ByteBuffer capacity
     * @param maxBucketSize the maximum number of ByteBuffers for each bucket
     */
    public ArrayByteBufferPool(int minCapacity, int factor, int maxCapacity, int maxBucketSize)
    {
        this(minCapacity, factor, maxCapacity, maxBucketSize, 0L, 0L);
    }

    /**
     * Creates a new ArrayByteBufferPool with the given configuration.
     *
     * @param minCapacity the minimum ByteBuffer capacity
     * @param factor the capacity factor
     * @param maxCapacity the maximum ByteBuffer capacity
     * @param maxBucketSize the maximum number of ByteBuffers for each bucket
     * @param maxHeapMemory the max heap memory in bytes, -1 for unlimited memory or 0 to use default heuristic
     * @param maxDirectMemory the max direct memory in bytes, -1 for unlimited memory or 0 to use default heuristic
     */
    public ArrayByteBufferPool(int minCapacity, int factor, int maxCapacity, int maxBucketSize, long maxHeapMemory, long maxDirectMemory)
    {
        this(minCapacity, factor, maxCapacity, maxBucketSize, maxHeapMemory, maxDirectMemory, null, null);
    }

    /**
     * Creates a new ArrayByteBufferPool with the given configuration.
     *
     * @param minCapacity the minimum ByteBuffer capacity
     * @param factor the capacity factor
     * @param maxCapacity the maximum ByteBuffer capacity
     * @param maxBucketSize the maximum number of ByteBuffers for each bucket
     * @param maxHeapMemory the max heap memory in bytes, -1 for unlimited memory or 0 to use default heuristic
     * @param maxDirectMemory the max direct memory in bytes, -1 for unlimited memory or 0 to use default heuristic
     * @param bucketIndexFor a {@link IntUnaryOperator} that takes a capacity and returns a bucket index
     * @param bucketCapacity a {@link IntUnaryOperator} that takes a bucket index and returns a capacity
     */
    protected ArrayByteBufferPool(int minCapacity, int factor, int maxCapacity, int maxBucketSize, long maxHeapMemory, long maxDirectMemory, IntUnaryOperator bucketIndexFor, IntUnaryOperator bucketCapacity)
    {
        if (minCapacity <= 0)
            minCapacity = 0;
        factor = factor <= 0 ? DEFAULT_FACTOR : factor;
        if (maxCapacity <= 0)
            maxCapacity = DEFAULT_MAX_CAPACITY_BY_FACTOR * factor;
        if ((maxCapacity % factor) != 0 || factor >= maxCapacity)
            throw new IllegalArgumentException(String.format("The capacity factor(%d) must be a divisor of maxCapacity(%d)", factor, maxCapacity));

        int f = factor;
        if (bucketIndexFor == null)
            bucketIndexFor = c -> (c - 1) / f;
        if (bucketCapacity == null)
            bucketCapacity = i -> (i + 1) * f;

        int length = bucketIndexFor.applyAsInt(maxCapacity) + 1;
        RetainedBucket[] directArray = new RetainedBucket[length];
        RetainedBucket[] indirectArray = new RetainedBucket[length];
        for (int i = 0; i < directArray.length; i++)
        {
            int capacity = Math.min(bucketCapacity.applyAsInt(i), maxCapacity);
            directArray[i] = new RetainedBucket(capacity, maxBucketSize);
            indirectArray[i] = new RetainedBucket(capacity, maxBucketSize);
        }

        _minCapacity = minCapacity;
        _maxCapacity = maxCapacity;
        _direct = directArray;
        _indirect = indirectArray;
        _maxHeapMemory = maxMemory(maxHeapMemory);
        _maxDirectMemory = maxMemory(maxDirectMemory);
        _bucketIndexFor = bucketIndexFor;
    }

    private long maxMemory(long maxMemory)
    {
        if (maxMemory < 0)
            return -1;
        if (maxMemory == 0)
            return Runtime.getRuntime().maxMemory() / 8;
        return maxMemory;
    }

    @ManagedAttribute("The minimum pooled buffer capacity")
    public int getMinCapacity()
    {
        return _minCapacity;
    }

    @ManagedAttribute("The maximum pooled buffer capacity")
    public int getMaxCapacity()
    {
        return _maxCapacity;
    }

    @Override
    public RetainableByteBuffer acquire(int size, boolean direct)
    {
        RetainedBucket bucket = bucketFor(size, direct);

        // No bucket, return non-pooled.
        if (bucket == null)
            return newRetainableByteBuffer(size, direct, null);

        // Try to acquire a pooled entry.
        Pool.Entry<RetainableByteBuffer> entry = bucket.getPool().acquire();
        if (entry != null)
        {
            RetainableByteBuffer buffer = entry.getPooled();
            ((Buffer)buffer).acquire();
            return buffer;
        }

        // No pooled entry available, try to reserve.
        Pool.Entry<RetainableByteBuffer> reservedEntry = bucket.getPool().reserve();
        // Cannot reserve, return non-pooled.
        if (reservedEntry == null)
            return newRetainableByteBuffer(size, direct, null);

        // Allocate the buffer that will be pooled.
        RetainableByteBuffer buffer = newRetainableByteBuffer(bucket.getCapacity(), direct, retainedBuffer ->
        {
            BufferUtil.reset(retainedBuffer.getByteBuffer());
            release(bucket, reservedEntry, direct);
        });

        reservedEntry.enable(buffer, true);

        return buffer;
    }

    private void release(RetainedBucket bucket, Pool.Entry<RetainableByteBuffer> entry, boolean direct)
    {
        long maxMemory = direct ? _maxDirectMemory : _maxHeapMemory;
        // Account also for the entry that is about to be released.
        long memory = getMemory(direct) + bucket.getCapacity();
        if (maxMemory > 0 && memory > maxMemory)
        {
            bucket.evict(entry);
        }
        else
        {
            if (!entry.release())
                entry.remove();
        }
    }

    protected ByteBuffer allocate(int capacity)
    {
        return ByteBuffer.allocate(capacity);
    }

    protected ByteBuffer allocateDirect(int capacity)
    {
        return ByteBuffer.allocateDirect(capacity);
    }

    private RetainableByteBuffer newRetainableByteBuffer(int capacity, boolean direct, Consumer<RetainableByteBuffer> releaser)
    {
        ByteBuffer buffer = BufferUtil.allocate(capacity, direct);
        Buffer retainableByteBuffer = new Buffer(buffer, releaser);
        retainableByteBuffer.acquire();
        return retainableByteBuffer;
    }

    public Pool<RetainableByteBuffer> poolFor(int capacity, boolean direct)
    {
        RetainedBucket bucket = bucketFor(capacity, direct);
        return bucket == null ? null : bucket.getPool();
    }

    private RetainedBucket bucketFor(int capacity, boolean direct)
    {
        if (capacity < _minCapacity)
            return null;
        int idx = _bucketIndexFor.applyAsInt(capacity);
        RetainedBucket[] buckets = direct ? _direct : _indirect;
        if (idx >= buckets.length)
            return null;
        return buckets[idx];
    }

    @ManagedAttribute("The number of pooled direct ByteBuffers")
    public long getDirectByteBufferCount()
    {
        return getByteBufferCount(true);
    }

    @ManagedAttribute("The number of pooled heap ByteBuffers")
    public long getHeapByteBufferCount()
    {
        return getByteBufferCount(false);
    }

    private long getByteBufferCount(boolean direct)
    {
        RetainedBucket[] buckets = direct ? _direct : _indirect;
        return Arrays.stream(buckets).mapToLong(bucket -> bucket.getPool().size()).sum();
    }

    @ManagedAttribute("The number of pooled direct ByteBuffers that are available")
    public long getAvailableDirectByteBufferCount()
    {
        return getAvailableByteBufferCount(true);
    }

    @ManagedAttribute("The number of pooled heap ByteBuffers that are available")
    public long getAvailableHeapByteBufferCount()
    {
        return getAvailableByteBufferCount(false);
    }

    private long getAvailableByteBufferCount(boolean direct)
    {
        RetainedBucket[] buckets = direct ? _direct : _indirect;
        return Arrays.stream(buckets).mapToLong(bucket -> bucket.getPool().getIdleCount()).sum();
    }

    @ManagedAttribute("The bytes retained by direct ByteBuffers")
    public long getDirectMemory()
    {
        return getMemory(true);
    }

    @ManagedAttribute("The bytes retained by heap ByteBuffers")
    public long getHeapMemory()
    {
        return getMemory(false);
    }

    private long getMemory(boolean direct)
    {
        long memory = 0;
        RetainedBucket[] buckets = direct ? _direct : _indirect;
        for (RetainedBucket bucket : buckets)
        {
            memory += bucket.getMemory();
        }
        return memory;
    }

    @ManagedAttribute("The available bytes retained by direct ByteBuffers")
    public long getAvailableDirectMemory()
    {
        return getAvailableMemory(true);
    }

    @ManagedAttribute("The available bytes retained by heap ByteBuffers")
    public long getAvailableHeapMemory()
    {
        return getAvailableMemory(false);
    }

    private long getAvailableMemory(boolean direct)
    {
        RetainedBucket[] buckets = direct ? _direct : _indirect;
        long total = 0L;
        for (RetainedBucket bucket : buckets)
        {
            long capacity = bucket.getCapacity();
            total += bucket.getPool().getIdleCount() * capacity;
        }
        return total;
    }

    @ManagedOperation(value = "Clears this ByteBufferPool", impact = "ACTION")
    public void clear()
    {
        clearBuckets(_direct);
        clearBuckets(_indirect);
    }

    private void clearBuckets(RetainedBucket[] buckets)
    {
        for (RetainedBucket bucket : buckets)
        {
            bucket.clear();
        }
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        Dumpable.dumpObjects(
            out,
            indent,
            this,
            DumpableCollection.fromArray("direct", _direct),
            DumpableCollection.fromArray("indirect", _indirect));
    }

    @Override
    public String toString()
    {
        return String.format("%s{min=%d,max=%d,buckets=%d,heap=%d/%d,direct=%d/%d}",
            super.toString(),
            _minCapacity, _maxCapacity,
            _direct.length,
            getMemory(false), _maxHeapMemory,
            getMemory(true), _maxDirectMemory);
    }

    private static class RetainedBucket
    {
        private final AtomicLong _memory = new AtomicLong();
        private final Pool<RetainableByteBuffer> _pool;
        private final int _capacity;

        private RetainedBucket(int capacity, int poolSize)
        {
            if (poolSize <= ConcurrentPool.OPTIMAL_MAX_SIZE)
                _pool = new ConcurrentBucket(ConcurrentPool.StrategyType.THREAD_ID, poolSize, e -> 1);
            else
                _pool = new CompoundBucket(
                    new ConcurrentBucket(ConcurrentPool.StrategyType.THREAD_ID, ConcurrentPool.OPTIMAL_MAX_SIZE, e -> 1),
                    new QueuedBucket(poolSize - ConcurrentPool.OPTIMAL_MAX_SIZE)
                );
            _capacity = capacity;
        }

        private int getCapacity()
        {
            return _capacity;
        }

        private long getMemory()
        {
            return _memory.get();
        }

        private Pool<RetainableByteBuffer> getPool()
        {
            return _pool;
        }

        private void evict(Pool.Entry<RetainableByteBuffer> entry)
        {
            if (_pool instanceof CompoundBucket compoundBucket)
                compoundBucket.evict(entry);
            else
                entry.remove();
        }

        public void clear()
        {
            getPool().stream().forEach(entry ->
            {
                if (entry.remove())
                    _memory.addAndGet(-_capacity);
            });
        }

        @Override
        public String toString()
        {
            int entries = 0;
            int inUse = 0;
            for (Pool.Entry<RetainableByteBuffer> entry : getPool().stream().toList())
            {
                entries++;
                if (entry.isInUse())
                    inUse++;
            }

            return String.format("%s{capacity=%d,inuse=%d(%d%%)}",
                super.toString(),
                getCapacity(),
                inUse,
                entries > 0 ? (inUse * 100) / entries : 0);
        }

        private class CompoundBucket extends CompoundPool<RetainableByteBuffer>
        {
            private final QueuedBucket queuedBucket;

            private CompoundBucket(ConcurrentBucket concurrentBucket, QueuedBucket queuedBucket)
            {
                super(concurrentBucket, queuedBucket);
                this.queuedBucket = queuedBucket;
            }

            private void evict(Entry<RetainableByteBuffer> entry)
            {
                if (entry instanceof ConcurrentPool.ConcurrentEntry<RetainableByteBuffer> concurrentEntry)
                {
                    Entry<RetainableByteBuffer> queuedEntry = queuedBucket.acquire();
                    if (queuedEntry != null)
                    {
                        queuedEntry.remove();
                        if (!concurrentEntry.release())
                            concurrentEntry.remove();
                    }
                    else
                    {
                        concurrentEntry.remove();
                    }
                }
                else
                {
                    entry.remove();
                }
            }
        }

        private class ConcurrentBucket extends ConcurrentPool<RetainableByteBuffer>
        {
            private ConcurrentBucket(StrategyType strategyType, int maxSize, ToIntFunction<RetainableByteBuffer> maxMultiplex)
            {
                super(strategyType, maxSize, maxMultiplex);
            }

            @Override
            protected void onAcquired(Entry<RetainableByteBuffer> entry)
            {
                super.onAcquired(entry);
                _memory.addAndGet(-_capacity);
            }

            @Override
            protected void onReleased(Entry<RetainableByteBuffer> entry)
            {
                super.onReleased(entry);
                _memory.addAndGet(_capacity);
            }
        }

        private class QueuedBucket extends QueuedPool<RetainableByteBuffer>
        {
            private QueuedBucket(int maxSize)
            {
                super(maxSize);
            }

            @Override
            protected void onAcquired(Entry<RetainableByteBuffer> entry)
            {
                super.onAcquired(entry);
                _memory.addAndGet(-_capacity);
            }

            @Override
            protected void onReleased(Entry<RetainableByteBuffer> entry)
            {
                super.onReleased(entry);
                _memory.addAndGet(_capacity);
            }
        }
    }

    private static class Buffer extends AbstractRetainableByteBuffer
    {
        private final Consumer<RetainableByteBuffer> releaser;

        private Buffer(ByteBuffer buffer, Consumer<RetainableByteBuffer> releaser)
        {
            super(buffer);
            this.releaser = releaser;
        }

        @Override
        public boolean release()
        {
            boolean released = super.release();
            if (released)
            {
                if (releaser != null)
                    releaser.accept(this);
            }
            return released;
        }
    }

    /**
     * A variant of the {@link ArrayByteBufferPool} that
     * uses buckets of buffers that increase in size by a power of
     * 2 (eg 1k, 2k, 4k, 8k, etc.).
     */
    public static class Quadratic extends ArrayByteBufferPool
    {
        public Quadratic()
        {
            this(0, -1, Integer.MAX_VALUE);
        }

        public Quadratic(int minCapacity, int maxCapacity, int maxBucketSize)
        {
            this(minCapacity, maxCapacity, maxBucketSize, -1L, -1L);
        }

        public Quadratic(int minCapacity, int maxCapacity, int maxBucketSize, long maxHeapMemory, long maxDirectMemory)
        {
            super(minCapacity,
                -1,
                maxCapacity,
                maxBucketSize,
                maxHeapMemory,
                maxDirectMemory,
                c -> 32 - Integer.numberOfLeadingZeros(c - 1),
                i -> 1 << i
            );
        }
    }

    /**
     * <p>A variant of {@link ArrayByteBufferPool} that tracks buffer
     * acquires/releases, useful to identify buffer leaks.</p>
     * <p>Use {@link #getLeaks()} when the system is idle to get
     * the {@link Buffer}s that have been leaked, which contain
     * the stack trace information of where the buffer was acquired.</p>
     */
    public static class Tracking extends ArrayByteBufferPool
    {
        private static final Logger LOG = LoggerFactory.getLogger(Tracking.class);

        private final Set<Buffer> buffers = ConcurrentHashMap.newKeySet();

        public Tracking()
        {
            this(0, -1, Integer.MAX_VALUE);
        }

        public Tracking(int minCapacity, int maxCapacity, int maxBucketSize)
        {
            this(minCapacity, maxCapacity, maxBucketSize, -1L, -1L);
        }

        public Tracking(int minCapacity, int maxCapacity, int maxBucketSize, long maxHeapMemory, long maxDirectMemory)
        {
            super(minCapacity, -1, maxCapacity, maxBucketSize, maxHeapMemory, maxDirectMemory);
        }

        @Override
        public RetainableByteBuffer acquire(int size, boolean direct)
        {
            RetainableByteBuffer buffer = super.acquire(size, direct);
            Buffer wrapper = new Buffer(buffer, size);
            if (LOG.isDebugEnabled())
                LOG.debug("acquired {}", wrapper);
            buffers.add(wrapper);
            return wrapper;
        }

        public Set<Buffer> getLeaks()
        {
            return buffers;
        }

        public String dumpLeaks()
        {
            return getLeaks().stream()
                .map(Buffer::dump)
                .collect(Collectors.joining(System.lineSeparator()));
        }

        public class Buffer extends RetainableByteBuffer.Wrapper
        {
            private final int size;
            private final Instant acquireInstant;
            private final Throwable acquireStack;
            private final List<Throwable> retainStacks = new CopyOnWriteArrayList<>();
            private final List<Throwable> releaseStacks = new CopyOnWriteArrayList<>();
            private final List<Throwable> overReleaseStacks = new CopyOnWriteArrayList<>();

            private Buffer(RetainableByteBuffer wrapped, int size)
            {
                super(wrapped);
                this.size = size;
                this.acquireInstant = Instant.now();
                this.acquireStack = new Throwable();
            }

            public int getSize()
            {
                return size;
            }

            public Instant getAcquireInstant()
            {
                return acquireInstant;
            }

            public Throwable getAcquireStack()
            {
                return acquireStack;
            }

            @Override
            public void retain()
            {
                super.retain();
                retainStacks.add(new Throwable());
            }

            @Override
            public boolean release()
            {
                try
                {
                    boolean released = super.release();
                    if (released)
                    {
                        buffers.remove(this);
                        if (LOG.isDebugEnabled())
                            LOG.debug("released {}", this);
                    }
                    releaseStacks.add(new Throwable());
                    return released;
                }
                catch (IllegalStateException e)
                {
                    buffers.add(this);
                    overReleaseStacks.add(new Throwable());
                    throw e;
                }
            }

            public String dump()
            {
                StringWriter w = new StringWriter();
                PrintWriter pw = new PrintWriter(w);
                getAcquireStack().printStackTrace(pw);
                pw.println("\n" + retainStacks.size() + " retain(s)");
                for (Throwable retainStack : retainStacks)
                {
                    retainStack.printStackTrace(pw);
                }
                pw.println("\n" + releaseStacks.size() + " release(s)");
                for (Throwable releaseStack : releaseStacks)
                {
                    releaseStack.printStackTrace(pw);
                }
                pw.println("\n" + overReleaseStacks.size() + " over-release(s)");
                for (Throwable overReleaseStack : overReleaseStacks)
                {
                    overReleaseStack.printStackTrace(pw);
                }
                return "%s@%x of %d bytes on %s wrapping %s acquired at %s".formatted(getClass().getSimpleName(), hashCode(), getSize(), getAcquireInstant(), getWrapped(), w);
            }
        }
    }
}
