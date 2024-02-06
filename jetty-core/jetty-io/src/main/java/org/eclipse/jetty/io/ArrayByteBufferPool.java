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
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;
import java.util.stream.Collectors;

import org.eclipse.jetty.io.internal.CompoundPool;
import org.eclipse.jetty.io.internal.QueuedPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ConcurrentPool;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.component.DumpableMap;
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
    private final AtomicLong _currentHeapMemory = new AtomicLong();
    private final AtomicLong _currentDirectMemory = new AtomicLong();
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
        this(minCapacity, factor, maxCapacity, maxBucketSize, maxHeapMemory, maxDirectMemory, bucketIndexFor, bucketCapacity, ConcurrentPool.OPTIMAL_MAX_SIZE);
    }

    public ArrayByteBufferPool(int minCapacity, int factor, int maxCapacity, int maxBucketSize, long maxHeapMemory, long maxDirectMemory, IntUnaryOperator bucketIndexFor, IntUnaryOperator bucketCapacity, int concurrentPoolSize)
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
            directArray[i] = new RetainedBucket(capacity, maxBucketSize, concurrentPoolSize);
            indirectArray[i] = new RetainedBucket(capacity, maxBucketSize, concurrentPoolSize);
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
        if (bucket == null)
            return newRetainableByteBuffer(size, direct, this::removed);
        Pool.Entry<RetainableByteBuffer> entry = bucket.getPool().acquire();

        RetainableByteBuffer buffer;
        if (entry == null)
        {
            Pool.Entry<RetainableByteBuffer> reservedEntry = bucket.getPool().reserve();
            if (reservedEntry != null)
            {
                buffer = newRetainableByteBuffer(bucket._capacity, direct, retainedBuffer ->
                {
                    BufferUtil.reset(retainedBuffer.getByteBuffer());
                    if (!reservedEntry.release())
                        reservedEntry.remove();
                });

                // A reserved entry may become old and be evicted before it is enabled.
                if (reservedEntry.enable(buffer, true))
                {
                    if (direct)
                        _currentDirectMemory.addAndGet(buffer.capacity());
                    else
                        _currentHeapMemory.addAndGet(buffer.capacity());
                    releaseExcessMemory(direct);
                    return buffer;
                }
            }
            return newRetainableByteBuffer(size, direct, this::removed);
        }

        buffer = entry.getPooled();
        ((Buffer)buffer).acquire();
        return buffer;
    }

    protected ByteBuffer allocate(int capacity)
    {
        return ByteBuffer.allocate(capacity);
    }

    protected ByteBuffer allocateDirect(int capacity)
    {
        return ByteBuffer.allocateDirect(capacity);
    }

    protected void removed(RetainableByteBuffer retainedBuffer)
    {
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
        RetainedBucket bucket = buckets[idx];
        bucket.acquire(capacity);
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
        if (direct)
            return _currentDirectMemory.get();
        else
            return _currentHeapMemory.get();
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
            long capacity = bucket._capacity;
            total += bucket.getPool().getIdleCount() * capacity;
        }
        return total;
    }

    @ManagedOperation(value = "Clears this ByteBufferPool", impact = "ACTION")
    public void clear()
    {
        clearArray(_direct, _currentDirectMemory);
        clearArray(_indirect, _currentHeapMemory);
    }

    private void clearArray(RetainedBucket[] poolArray, AtomicLong memoryCounter)
    {
        for (RetainedBucket bucket : poolArray)
        {
            bucket.getPool().stream().forEach(entry ->
            {
                if (entry.remove())
                {
                    RetainableByteBuffer pooled = entry.getPooled();
                    // Calling getPooled can return null if the entry was not yet enabled.
                    if (pooled != null)
                    {
                        memoryCounter.addAndGet(-pooled.capacity());
                        removed(pooled);
                    }
                }
            });
        }
    }

    private void releaseExcessMemory(boolean direct)
    {
        long maxMemory = direct ? _maxDirectMemory : _maxHeapMemory;
        if (maxMemory > 0)
        {
            long excess = getMemory(direct) - maxMemory;
            if (excess > 0)
                evict(direct, excess);
        }
    }

    /**
     * This eviction mechanism searches for the RetainableByteBuffers that were released the longest time ago.
     *
     * @param direct true to search in the direct buffers buckets, false to search in the heap buffers buckets.
     * @param excess the amount of bytes to evict. At least this much will be removed from the buckets.
     */
    private void evict(boolean direct, long excess)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("evicting {} bytes from {} pools", excess, (direct ? "direct" : "heap"));
        long now = NanoTime.now();
        long totalClearedCapacity = 0L;

        RetainedBucket[] buckets = direct ? _direct : _indirect;

        while (totalClearedCapacity < excess)
        {
            // Run through all the buckets to avoid removing
            // the buffers only from the first bucket(s).
            for (RetainedBucket bucket : buckets)
            {
                Pool.Entry<RetainableByteBuffer> oldestEntry = findOldestEntry(now, bucket.getPool());
                if (oldestEntry == null)
                    continue;

                // Get the pooled buffer now in case we can evict below.
                // The buffer may be null if the entry has been reserved but
                // not yet enabled, or the entry has been removed by a concurrent
                // thread, that may also have nulled-out the pooled buffer.
                RetainableByteBuffer buffer = oldestEntry.getPooled();
                if (buffer == null)
                    continue;

                // A concurrent thread evicted the same entry.
                // A successful Entry.remove() call may null-out the pooled buffer.
                if (!oldestEntry.remove())
                    continue;

                // We can evict, clear the buffer capacity.
                int clearedCapacity = buffer.capacity();
                if (direct)
                    _currentDirectMemory.addAndGet(-clearedCapacity);
                else
                    _currentHeapMemory.addAndGet(-clearedCapacity);
                totalClearedCapacity += clearedCapacity;
                removed(buffer);
            }
        }

        if (LOG.isDebugEnabled())
            LOG.debug("eviction done, cleared {} bytes from {} pools", totalClearedCapacity, (direct ? "direct" : "heap"));
    }

    @Override
    public String toString()
    {
        return String.format("%s{min=%d,max=%d,buckets=%d,heap=%d/%d,direct=%d/%d}",
            super.toString(),
            _minCapacity, _maxCapacity,
            _direct.length,
            _currentHeapMemory.get(), _maxHeapMemory,
            _currentDirectMemory.get(), _maxDirectMemory);
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

    private Pool.Entry<RetainableByteBuffer> findOldestEntry(long now, Pool<RetainableByteBuffer> bucket)
    {
        // This method may be in the hot path, do not use Java streams.

        Pool.Entry<RetainableByteBuffer> oldestEntry = null;
        RetainableByteBuffer oldestBuffer = null;
        long oldestAge = 0;
        // TODO: improve Pool APIs to avoid stream().toList().
        for (Pool.Entry<RetainableByteBuffer> entry : bucket.stream().toList())
        {
            Buffer buffer = (Buffer)entry.getPooled();
            // A null buffer means the entry is reserved
            // but not acquired yet, try the next.
            if (buffer != null)
            {
                long age = NanoTime.elapsed(buffer.getLastNanoTime(), now);
                if (oldestBuffer == null || age > oldestAge)
                {
                    oldestEntry = entry;
                    oldestBuffer = buffer;
                    oldestAge = age;
                }
            }
        }
        return oldestEntry;
    }

    private static class RetainedBucket
    {
        private final Pool<RetainableByteBuffer> _pool;
        private final ConcurrentPool<RetainableByteBuffer> _concurrentPool;
        private final int _capacity;
        private final LongAdder _acquisitions = new LongAdder();
        private final LongAdder _waste = new LongAdder();

        private RetainedBucket(int capacity, int maxPoolSize, int concurrentPoolSize)
        {
            if (maxPoolSize <= concurrentPoolSize)
                _pool = _concurrentPool = new ConcurrentPool<>(ConcurrentPool.StrategyType.THREAD_ID, maxPoolSize, e -> 1);
            else
                _pool = new CompoundPool<>(
                    _concurrentPool = new ConcurrentPool<>(ConcurrentPool.StrategyType.THREAD_ID, concurrentPoolSize, e -> 1),
                    new QueuedPool<>(maxPoolSize - concurrentPoolSize)
                );
            _capacity = capacity;
        }

        private Pool<RetainableByteBuffer> getPool()
        {
            return _pool;
        }

        private void acquire(int capacity)
        {
            _acquisitions.increment();
            _waste.add(_capacity - capacity);
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

            long acquisitions = _acquisitions.longValue();
            long avgWaste = acquisitions == 0 ? 0 : _waste.longValue() / acquisitions;
            long poolAcquisitions = _concurrentPool.getAcquisitions();
            long poolProbes = _concurrentPool.getProbes();
            long poolAvgProbes = poolAcquisitions == 0 ? 0 : poolProbes / poolAcquisitions;
            return String.format("%s{capacity=%d,entries=%d,inuse=%d(%d%%),avgwaste=%d(%d),ConcurrentPool{sz=%d,acq=%d,avgprb=%d(%d),lkd=%d}}",
                super.toString(),
                _capacity,
                entries,
                inUse,
                entries > 0 ? (inUse * 100) / entries : 0,
                avgWaste, acquisitions,
                _concurrentPool.size(), poolAcquisitions, poolAvgProbes, poolProbes, _concurrentPool.getLeaked());
        }
    }

    private static class Buffer extends AbstractRetainableByteBuffer
    {
        private final Consumer<RetainableByteBuffer> releaser;
        private final AtomicLong lastNanoTime = new AtomicLong(NanoTime.now());

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
                lastNanoTime.setOpaque(NanoTime.now());
                releaser.accept(this);
            }
            return released;
        }

        public long getLastNanoTime()
        {
            return lastNanoTime.getOpaque();
        }
    }

    /**
     * A variant of the {@link ArrayByteBufferPool} that
     * collects statistics about the requested buffer sizes.
     */
    public static class Statistical extends ArrayByteBufferPool
    {
        private final ConcurrentMap<Integer, Long> _requestedBufferSizes = new ConcurrentHashMap<>();

        public Statistical()
        {
        }

        public Statistical(int minCapacity, int factor, int maxCapacity)
        {
            super(minCapacity, factor, maxCapacity);
        }

        public Statistical(int minCapacity, int factor, int maxCapacity, int maxBucketSize)
        {
            super(minCapacity, factor, maxCapacity, maxBucketSize);
        }

        public Statistical(int minCapacity, int factor, int maxCapacity, int maxBucketSize, long maxHeapMemory, long maxDirectMemory)
        {
            super(minCapacity, factor, maxCapacity, maxBucketSize, maxHeapMemory, maxDirectMemory);
        }

        public Statistical(int minCapacity, int factor, int maxCapacity, int maxBucketSize, long maxHeapMemory, long maxDirectMemory, IntUnaryOperator bucketIndexFor, IntUnaryOperator bucketCapacity)
        {
            super(minCapacity, factor, maxCapacity, maxBucketSize, maxHeapMemory, maxDirectMemory, bucketIndexFor, bucketCapacity);
        }

        public Statistical(int minCapacity, int factor, int maxCapacity, int maxBucketSize, long maxHeapMemory, long maxDirectMemory, IntUnaryOperator bucketIndexFor, IntUnaryOperator bucketCapacity, int concurrentPoolSize)
        {
            super(minCapacity, factor, maxCapacity, maxBucketSize, maxHeapMemory, maxDirectMemory, bucketIndexFor, bucketCapacity, concurrentPoolSize);
        }

        @Override
        public RetainableByteBuffer acquire(int size, boolean direct)
        {
            _requestedBufferSizes.compute(size, (key, oldValue) ->
            {
                if (oldValue == null)
                    return 1L;
                return oldValue + 1L;
            });
            return super.acquire(size, direct);
        }

        @Override
        public void dump(Appendable out, String indent) throws IOException
        {
            Dumpable.dumpObjects(
                out,
                indent,
                this,
                DumpableMap.from("requested buffer sizes", _requestedBufferSizes, true),
                DumpableCollection.fromArray("direct", super._direct),
                DumpableCollection.fromArray("indirect", super._indirect));
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
