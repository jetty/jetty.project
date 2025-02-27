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
import java.lang.reflect.RecordComponent;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntUnaryOperator;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

import org.eclipse.jetty.io.internal.CompoundPool;
import org.eclipse.jetty.io.internal.QueuedPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ConcurrentPool;
import org.eclipse.jetty.util.MathUtils;
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
    static final int DEFAULT_FACTOR = 4096;
    static final int DEFAULT_MAX_CAPACITY_BY_FACTOR = 16;

    private final RetainedBucket[] _direct;
    private final RetainedBucket[] _indirect;
    private final int _minCapacity;
    private final int _maxCapacity;
    private final long _maxHeapMemory;
    private final long _maxDirectMemory;
    private final IntUnaryOperator _bucketIndexFor;
    private final IntUnaryOperator _bucketCapacity;
    private final AtomicBoolean _evictor = new AtomicBoolean(false);
    private final AtomicLong _reserved = new AtomicLong();
    private final ConcurrentMap<Integer, Long> _noBucketDirectAcquires = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, Long> _noBucketIndirectAcquires = new ConcurrentHashMap<>();
    private boolean _statisticsEnabled;

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
        _bucketCapacity = bucketCapacity;
    }

    private long maxMemory(long maxMemory)
    {
        if (maxMemory < 0)
            return -1;
        if (maxMemory == 0)
            return Runtime.getRuntime().maxMemory() / 8;
        return maxMemory;
    }

    @ManagedAttribute("The current number of allocated bytes reserved to be added to the pool once released")
    public long getReserved()
    {
        return _reserved.get();
    }

    @ManagedAttribute("Whether statistics are enabled")
    public boolean isStatisticsEnabled()
    {
        return _statisticsEnabled;
    }

    public void setStatisticsEnabled(boolean enabled)
    {
        _statisticsEnabled = enabled;
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
    public RetainableByteBuffer.Mutable acquire(int size, boolean direct)
    {
        RetainedBucket bucket = bucketFor(size, direct);

        // No bucket, return non-pooled.
        if (bucket == null)
        {
            recordNoBucketAcquire(size, direct);
            return RetainableByteBuffer.wrap(BufferUtil.allocate(size, direct));
        }

        bucket.recordAcquire(size);

        // Try to acquire a pooled entry.
        Pool.Entry<RetainableByteBuffer.Pooled> entry = bucket.getPool().acquire();
        if (entry == null)
        {
            ByteBuffer buffer = BufferUtil.allocate(bucket.getCapacity(), direct);
            _reserved.addAndGet(buffer.capacity());
            return new ReservedBuffer(buffer, bucket);
        }

        bucket.recordPooled();
        RetainableByteBuffer.Pooled buffer = entry.getPooled();
        ((PooledBuffer)buffer).acquire();
        return buffer;
    }

    private void recordNoBucketAcquire(int size, boolean direct)
    {
        if (isStatisticsEnabled())
        {
            ConcurrentMap<Integer, Long> map = direct ? _noBucketDirectAcquires : _noBucketIndirectAcquires;
            int idx = _bucketIndexFor.applyAsInt(size);
            int key = _bucketCapacity.applyAsInt(idx);
            map.compute(key, (k, v) ->
            {
                if (v == null)
                    return 1L;
                return v + 1L;
            });
        }
    }

    private void reserve(RetainedBucket bucket, ByteBuffer byteBuffer)
    {
        _reserved.addAndGet(-byteBuffer.capacity());
        bucket.recordRelease();

        // Try to reserve an entry to put the buffer into the pool.
        Pool.Entry<RetainableByteBuffer.Pooled> entry = bucket.getPool().reserve();
        if (entry == null)
        {
            bucket.recordNonPooled();
            return;
        }

        // Add the buffer to the new entry.
        BufferUtil.reset(byteBuffer);
        PooledBuffer pooledBuffer = new PooledBuffer(byteBuffer, bucket, entry);
        if (entry.enable(pooledBuffer, false))
        {
            checkMaxMemory(bucket, byteBuffer.isDirect());
            return;
        }

        // Discard the buffer if the entry cannot be enabled.
        bucket.recordNonPooled();
        entry.remove();
    }

    private void release(RetainedBucket bucket, Pool.Entry<RetainableByteBuffer.Pooled> entry)
    {
        bucket.recordRelease();

        RetainableByteBuffer buffer = entry.getPooled();
        BufferUtil.reset(buffer.getByteBuffer());

        // Release the buffer and check the memory 1% of the times.
        int used = ((PooledBuffer)buffer).use();
        if (entry.release())
        {
            if (used % 100 == 0)
               checkMaxMemory(bucket, buffer.isDirect());
            return;
        }

        // Cannot release, discard this buffer.
        bucket.recordRemove();
        entry.remove();
    }

    private boolean remove(RetainedBucket bucket, Pool.Entry<RetainableByteBuffer.Pooled> entry)
    {
        // Cannot release, discard this buffer.
        bucket.recordRemove();
        return entry.remove();
    }

    private void checkMaxMemory(RetainedBucket bucket, boolean direct)
    {
        long max = direct ? _maxDirectMemory : _maxHeapMemory;
        if (max <= 0 || !_evictor.compareAndSet(false, true))
            return;
        try
        {
            long memory = getTotalMemory(direct);
            long excess = memory - max;
            if (excess > 0)
            {
                bucket.recordEvict();
                evict(excess, direct);
            }
        }
        finally
        {
            _evictor.set(false);
        }
    }

    private void evict(long excessMemory, boolean direct)
    {
        RetainedBucket[] buckets = direct ? _direct : _indirect;
        int length = buckets.length;
        int index = ThreadLocalRandom.current().nextInt(length);
        for (int c = 0; c < length; ++c)
        {
            RetainedBucket bucket = buckets[index++];
            if (index == length)
                index = 0;

            int evicted = bucket.evict();
            excessMemory -= evicted;
            if (excessMemory <= 0)
                return;
        }
    }

    public Pool<RetainableByteBuffer.Pooled> poolFor(int capacity, boolean direct)
    {
        RetainedBucket bucket = bucketFor(capacity, direct);
        return bucket == null ? null : bucket.getPool();
    }

    private RetainedBucket bucketFor(int capacity, boolean direct)
    {
        if (capacity < getMinCapacity())
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

    @ManagedAttribute("The total bytes retained by direct ByteBuffers")
    public long getDirectMemory()
    {
        return getTotalMemory(true);
    }

    @ManagedAttribute("The total bytes retained by heap ByteBuffers")
    public long getHeapMemory()
    {
        return getTotalMemory(false);
    }

    private long getTotalMemory(boolean direct)
    {
        return getMemory(direct, bucket -> bucket.getPool().size());
    }

    private long getMemory(boolean direct, ToLongFunction<RetainedBucket> count)
    {
        long size = 0;
        for (RetainedBucket bucket : direct ? _direct : _indirect)
            size += count.applyAsLong(bucket) * bucket.getCapacity();
        return size;
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
        return getMemory(direct, bucket -> bucket.getPool().getIdleCount());
    }

    @ManagedAttribute("The heap buckets statistics")
    public List<Map<String, Object>> getHeapBucketsStatistics()
    {
        return getBucketsStatistics(false);
    }

    @ManagedAttribute("The direct buckets statistics")
    public List<Map<String, Object>> getDirectBucketsStatistics()
    {
        return getBucketsStatistics(true);
    }

    private List<Map<String, Object>> getBucketsStatistics(boolean direct)
    {
        RetainedBucket[] buckets = direct ? _direct : _indirect;
        return Arrays.stream(buckets).map(b -> b.getStatistics().toMap()).toList();
    }

    @ManagedAttribute("The acquires for direct non-pooled bucket capacities")
    public Map<Integer, Long> getNoBucketDirectAcquires()
    {
        return getNoBucketAcquires(true);
    }

    @ManagedAttribute("The acquires for heap non-pooled bucket capacities")
    public Map<Integer, Long> getNoBucketHeapAcquires()
    {
        return getNoBucketAcquires(false);
    }

    private Map<Integer, Long> getNoBucketAcquires(boolean direct)
    {
        return new HashMap<>(direct ? _noBucketDirectAcquires : _noBucketIndirectAcquires);
    }

    @ManagedOperation(value = "Clears this ByteBufferPool", impact = "ACTION")
    public void clear()
    {
        clearBuckets(_direct);
        _noBucketDirectAcquires.clear();
        clearBuckets(_indirect);
        _noBucketIndirectAcquires.clear();
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
            new DumpableMap("direct non-pooled acquisitions", _noBucketDirectAcquires),
            DumpableCollection.fromArray("indirect", _indirect),
            new DumpableMap("heap non-pooled acquisitions", _noBucketIndirectAcquires)
        );
    }

    @Override
    public String toString()
    {
        return String.format("%s{min=%d,max=%d,buckets=%d,heap=%d/%d,direct=%d/%d}",
            super.toString(),
            _minCapacity, _maxCapacity,
            _direct.length,
            getHeapMemory(), _maxHeapMemory,
            getDirectMemory(), _maxDirectMemory);
    }

    private class RetainedBucket
    {
        private final LongAdder _acquires = new LongAdder();
        private final LongAdder _totalAcquired = new LongAdder();
        private final LongAdder _pooled = new LongAdder();
        private final LongAdder _nonPooled = new LongAdder();
        private final LongAdder _evicts = new LongAdder();
        private final LongAdder _removes = new LongAdder();
        private final LongAdder _releases = new LongAdder();
        private final Pool<RetainableByteBuffer.Pooled> _pool;
        private final int _capacity;

        private RetainedBucket(int capacity, int poolSize)
        {
            if (poolSize <= ConcurrentPool.OPTIMAL_MAX_SIZE)
                _pool = new ConcurrentPool<>(ConcurrentPool.StrategyType.THREAD_ID, poolSize, e -> 1);
            else
                _pool = new BucketCompoundPool(
                    new ConcurrentPool<>(ConcurrentPool.StrategyType.THREAD_ID, ConcurrentPool.OPTIMAL_MAX_SIZE, e -> 1),
                    new QueuedPool<>(poolSize - ConcurrentPool.OPTIMAL_MAX_SIZE)
                );
            _capacity = capacity;
        }

        public void recordAcquire(int size)
        {
            if (isStatisticsEnabled())
            {
                _acquires.increment();
                _totalAcquired.add(size);
            }
        }

        public void recordEvict()
        {
            if (isStatisticsEnabled())
                _evicts.increment();
        }

        public void recordNonPooled()
        {
            if (isStatisticsEnabled())
                _nonPooled.increment();
        }

        public void recordPooled()
        {
            if (isStatisticsEnabled())
                _pooled.increment();
        }

        public void recordRelease()
        {
            if (isStatisticsEnabled())
                _releases.increment();
        }

        public void recordRemove()
        {
            if (isStatisticsEnabled())
                _removes.increment();
        }

        private int getCapacity()
        {
            return _capacity;
        }

        private Pool<RetainableByteBuffer.Pooled> getPool()
        {
            return _pool;
        }

        private int evict()
        {
            Pool.Entry<RetainableByteBuffer.Pooled> entry;
            if (_pool instanceof BucketCompoundPool compound)
                entry = compound.evict();
            else
                entry = _pool.acquire();

            if (entry == null)
                return 0;

            recordRemove();
            entry.remove();

            return getCapacity();
        }

        private Statistics getStatistics()
        {
            long pooled = _pooled.longValue();
            long acquires = _acquires.longValue();
            float hitRatio = acquires == 0 ? Float.NaN : pooled * 100F / acquires;
            int averageSize = acquires == 0 ? 0 : (int)(_totalAcquired.longValue() / acquires);
            return new Statistics(getCapacity(), getPool().getInUseCount(), getPool().size(), pooled, acquires,
                _releases.longValue(), hitRatio, averageSize, _nonPooled.longValue(), _evicts.longValue(), _removes.longValue());
        }

        public void clear()
        {
            _acquires.reset();
            _totalAcquired.reset();
            _pooled.reset();
            _nonPooled.reset();
            _evicts.reset();
            _removes.reset();
            _releases.reset();
            getPool().stream().forEach(Pool.Entry::remove);
        }

        @Override
        public String toString()
        {
            return String.format("%s[%s]", super.toString(), getStatistics());
        }

        private record Statistics(int capacity, int inUseEntries, int totalEntries, long pooled, long acquires,
                                  long releases, float hitRatio, int averageSize, long nonPooled, long evicts, long removes)
        {
            private Map<String, Object> toMap()
            {
                try
                {
                    Map<String, Object> statistics = new HashMap<>();
                    for (RecordComponent c : getClass().getRecordComponents())
                    {
                        statistics.put(c.getName(), c.getAccessor().invoke(this));
                    }
                    return statistics;
                }
                catch (Throwable x)
                {
                    return Map.of();
                }
            }

            @Override
            public String toString()
            {
                return "capacity=%d,in-use=%d/%d,pooled/acquires/releases=%d/%d/%d(%.3f%%),avgSize=%d,non-pooled/evicts/removes=%d/%d/%d".formatted(
                    capacity,
                    inUseEntries,
                    totalEntries,
                    pooled,
                    acquires,
                    releases,
                    hitRatio,
                    averageSize,
                    nonPooled,
                    evicts,
                    removes
                );
            }
        }

        private static class BucketCompoundPool extends CompoundPool<RetainableByteBuffer.Pooled>
        {
            private BucketCompoundPool(ConcurrentPool<RetainableByteBuffer.Pooled> concurrentBucket, QueuedPool<RetainableByteBuffer.Pooled> queuedBucket)
            {
                super(concurrentBucket, queuedBucket);
            }

            private Pool.Entry<RetainableByteBuffer.Pooled> evict()
            {
                Entry<RetainableByteBuffer.Pooled> entry = getSecondaryPool().acquire();
                if (entry == null)
                    entry = getPrimaryPool().acquire();
                return entry;
            }
        }
    }

    private class ReservedBuffer extends RetainableByteBuffer.Pooled
    {
        private final RetainedBucket _bucket;
        private final AtomicBoolean _removed = new AtomicBoolean();

        private ReservedBuffer(ByteBuffer buffer, RetainedBucket bucket)
        {
            super(ArrayByteBufferPool.this, buffer);
            _bucket = Objects.requireNonNull(bucket);
        }

        @Override
        public boolean release()
        {
            boolean released = super.release();
            if (released && _removed.compareAndSet(false, true))
                reserve(_bucket, getByteBuffer());
            return released;
        }

        void remove()
        {
            // Buffer never added to pool, so just prevent future reservation
            _removed.compareAndSet(false, true);
        }
    }

    private class PooledBuffer extends RetainableByteBuffer.Pooled
    {
        private final ReferenceCounter _referenceCounter;
        private final RetainedBucket _bucket;
        private final Pool.Entry<RetainableByteBuffer.Pooled> _entry;
        private int _usages;

        private PooledBuffer(ByteBuffer buffer, RetainedBucket bucket, Pool.Entry<RetainableByteBuffer.Pooled> entry)
        {
            super(ArrayByteBufferPool.this, buffer, new ReferenceCounter(0));
            if (getWrapped() instanceof  ReferenceCounter referenceCounter)
                _referenceCounter = referenceCounter;
            else
                throw new IllegalArgumentException();
            _bucket = Objects.requireNonNull(bucket);
            _entry = Objects.requireNonNull(entry);
        }

        @Override
        public boolean release()
        {
            boolean released = super.release();
            if (released)
                ArrayByteBufferPool.this.release(_bucket, _entry);
            return released;
        }

        void remove()
        {
            ArrayByteBufferPool.this.remove(_bucket, _entry);
        }

        private int use()
        {
            if (++_usages < 0)
                _usages = 0;
            return _usages;
        }

        /**
         * @see ReferenceCounter#acquire()
         */
        protected void acquire()
        {
            _referenceCounter.acquire();
        }
    }

    /**
     * A variant of the {@link ArrayByteBufferPool} that
     * uses a predefined set of buckets of buffers.
     */
    public static class WithBucketCapacities extends ArrayByteBufferPool
    {
        public WithBucketCapacities(int... capacities)
        {
            this(0L, 0L, capacities);
        }

        public WithBucketCapacities(long maxHeapMemory, long maxDirectMemory, int... capacities)
        {
            super(-1, 1, sort(capacities)[capacities.length - 1], Integer.MAX_VALUE, maxHeapMemory, maxDirectMemory,
                c -> floorBucketIndexFor(c, capacities), i -> bucketCapacityForIndex(i, capacities));
        }

        private static int[] sort(int... values)
        {
            if (values.length == 0)
                throw new IllegalArgumentException("At least one capacity is needed");
            Arrays.sort(values);
            return values;
        }

        private static int bucketCapacityForIndex(int idx, int... capacities)
        {
            if (idx >= capacities.length)
            {
                // An index over the capacities array's length is considered
                // to refer to a multiple of the largest configured capacity;
                // this logic is only meant for recordNoBucketAcquire().
                int largestCapacity = capacities[capacities.length - 1];
                int virtualIdx = idx - (capacities.length - 1);
                return (virtualIdx + 1) * largestCapacity;
            }
            return capacities[idx];
        }

        private static int floorBucketIndexFor(int capacity, int... capacities)
        {
            int largestCapacity = capacities[capacities.length - 1];
            if (capacity > largestCapacity)
            {
                // A capacity over the largest configured capacity returns an
                // index that corresponds to where in the capacities array it
                // would stand if the latter had more entries that would all
                // be multiples of the largest configured capacity;
                // this logic is only meant for recordNoBucketAcquire().
                int remainder = capacity % largestCapacity != 0 ? 1 : 0;
                int overLargestCapacityFactor = (capacity / largestCapacity) + remainder;
                return overLargestCapacityFactor - 1 + capacities.length - 1;
            }

            int idx = 0;
            for (int i = 0; i < capacities.length; i++)
            {
                idx = i;
                if (capacities[i] > capacity)
                    break;
            }
            return idx;
        }
    }

    /**
     * A variant of the {@link ArrayByteBufferPool} that
     * uses buckets of buffers that increase in size by a power of
     * 2 (e.g. 1k, 2k, 4k, 8k, etc.).
     */
    public static class Quadratic extends ArrayByteBufferPool
    {
        public Quadratic()
        {
            this(-1, -1, Integer.MAX_VALUE);
        }

        public Quadratic(int minCapacity, int maxCapacity, int maxBucketSize)
        {
            this(minCapacity, maxCapacity, maxBucketSize, 0L, 0L);
        }

        public Quadratic(int minCapacity, int maxCapacity, int maxBucketSize, long maxHeapMemory, long maxDirectMemory)
        {
            super(minCapacity,
                computeMinCapacity(minCapacity),
                computeMaxCapacity(maxCapacity),
                maxBucketSize,
                maxHeapMemory,
                maxDirectMemory,
                // The bucket indices are the powers of 2, but those powers up to minCapacity are skipped so they must be
                // substracted when computing the index and added when computing the capacity; so if minCapacity is 1024, any
                // number from 0 to 1024 must return index 0, and index 0 must return capacity 1024.
                c -> Integer.SIZE - Integer.numberOfLeadingZeros(c - 1) - MathUtils.ceilLog2(computeMinCapacity(minCapacity)),
                i -> 1 << i + MathUtils.ceilLog2(computeMinCapacity(minCapacity))
            );
        }

        private static int computeMinCapacity(int minCapacity)
        {
            return minCapacity <= 0 ? 1024 : minCapacity;
        }

        private static int computeMaxCapacity(int maxCapacity)
        {
            return maxCapacity <= 0 ? 65536 : maxCapacity;
        }
    }

    /**
     * <p>A variant of {@link ArrayByteBufferPool} that tracks buffer
     * acquires/releases, useful to identify buffer leaks.</p>
     * <p>Use {@link #getLeaks()} when the system is idle to get
     * the {@link TrackedBuffer}s that have been leaked, which contain
     * the stack trace information of where the buffer was acquired.</p>
     */
    public static class Tracking extends ArrayByteBufferPool
    {
        private static final Logger LOG = LoggerFactory.getLogger(Tracking.class);

        private final Set<TrackedBuffer> buffers = ConcurrentHashMap.newKeySet();

        public Tracking()
        {
            super();
        }

        public Tracking(int minCapacity, int maxCapacity, int maxBucketSize)
        {
            super(minCapacity, maxCapacity, maxBucketSize);
        }

        public Tracking(int minCapacity, int factor, int maxCapacity, int maxBucketSize)
        {
            super(minCapacity, factor, maxCapacity, maxBucketSize);
        }

        public Tracking(int minCapacity, int maxCapacity, int maxBucketSize, long maxHeapMemory, long maxDirectMemory)
        {
            super(minCapacity, -1, maxCapacity, maxBucketSize, maxHeapMemory, maxDirectMemory);
        }

        public Tracking(int minCapacity, int factor, int maxCapacity, int maxBucketSize, long maxHeapMemory, long maxDirectMemory)
        {
            super(minCapacity, factor, maxCapacity, maxBucketSize, maxHeapMemory, maxDirectMemory);
        }

        @Override
        public RetainableByteBuffer.Mutable acquire(int size, boolean direct)
        {
            RetainableByteBuffer.Mutable buffer = super.acquire(size, direct);
            TrackedBuffer wrapper = new TrackedBuffer(buffer, size);
            if (LOG.isDebugEnabled())
                LOG.debug("acquired {}", wrapper);
            buffers.add(wrapper);
            return wrapper;
        }

        public Set<TrackedBuffer> getLeaks()
        {
            return buffers;
        }

        public String dumpLeaks()
        {
            return getLeaks().stream()
                .map(TrackedBuffer::dump)
                .collect(Collectors.joining(System.lineSeparator()));
        }

        public class TrackedBuffer extends RetainableByteBuffer.FixedCapacity
        {
            private final int size;
            private final Instant acquireInstant;
            private final Throwable acquireStack;
            private final List<Throwable> retainStacks = new CopyOnWriteArrayList<>();
            private final List<Throwable> releaseStacks = new CopyOnWriteArrayList<>();
            private final List<Throwable> overReleaseStacks = new CopyOnWriteArrayList<>();

            private TrackedBuffer(RetainableByteBuffer.Mutable wrapped, int size)
            {
                super(wrapped.getByteBuffer(), wrapped);
                this.size = size;
                this.acquireInstant = Instant.now();
                this.acquireStack = new Throwable(Thread.currentThread().getName());
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
            public RetainableByteBuffer slice()
            {
                RetainableByteBuffer slice = super.slice();
                return new Mutable.Wrapper(slice)
                {
                    @Override
                    public boolean release()
                    {
                        return TrackedBuffer.this.release();
                    }
                };
            }

            @Override
            public RetainableByteBuffer slice(long length)
            {
                RetainableByteBuffer slice = super.slice(length);
                return new Mutable.Wrapper(slice)
                {
                    @Override
                    public boolean release()
                    {
                        return TrackedBuffer.this.release();
                    }
                };
            }

            @Override
            public void retain()
            {
                super.retain();
                retainStacks.add(new Throwable(Thread.currentThread().getName()));
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
                    overReleaseStacks.add(new Throwable(Thread.currentThread().getName()));
                    throw e;
                }
            }

            @Override
            protected void addExtraStringInfo(StringBuilder builder)
            {
                builder.append(",@");
                builder.append(Integer.toHexString(System.identityHashCode(getWrapped())));
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
                return "%s@%x of %d bytes on %s wrapping %s acquired at %s".formatted(getClass().getSimpleName(), hashCode(), getSize(), getAcquireInstant(), getRetained(), w);
            }
        }
    }
}
