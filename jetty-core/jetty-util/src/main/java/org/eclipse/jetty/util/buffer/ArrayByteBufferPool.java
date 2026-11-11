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

package org.eclipse.jetty.util.buffer;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.RecordComponent;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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

import org.eclipse.jetty.util.ConcurrentPool;
import org.eclipse.jetty.util.Pool;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.buffer.internal.CompoundPool;
import org.eclipse.jetty.util.buffer.internal.QueuedPool;
import org.eclipse.jetty.util.buffer.internal.SingleMutableBuffer;
import org.eclipse.jetty.util.component.Dumpable;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.component.DumpableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// A [ByteBufferPool] where [RetainableByteBuffer]s are pooled in an array
/// of buckets, each of a specific capacity.
///
/// The capacities of the buckets are configurable, as well as the max memory
/// pooled by all [RetainableByteBuffer]s in all the buckets.
@ManagedObject
public class ArrayByteBufferPool implements ByteBufferPool, Dumpable
{
    public static final int[] DEFAULT_BUCKETS = {
        64, // Frame headers, chunk sizes
        2 * 1024, // UDP datagrams
        4 * 1024,
        8 * 1024,
        16 * 1024,
        17 * 1024, // TLS record
        32 * 1024,
        64 * 1024
    };

    private final RetainedBucket[] _direct;
    private final RetainedBucket[] _indirect;
    private final int _minCapacity;
    private final int _maxCapacity;
    private final long _maxHeapMemory;
    private final long _maxDirectMemory;
    private final IntUnaryOperator _bucketIndexFor;
    private final IntUnaryOperator _bucketCapacityFor;
    private final AtomicBoolean _evictor = new AtomicBoolean(false);
    private final AtomicLong _reserved = new AtomicLong();
    private final ConcurrentHashMap<Integer, NoBucketData> _noBucketDirectAcquires = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, NoBucketData> _noBucketIndirectAcquires = new ConcurrentHashMap<>();
    private boolean _statisticsEnabled;

    /// Creates a new buffer pool with a default configuration.
    public ArrayByteBufferPool()
    {
        this(DEFAULT_BUCKETS);
    }

    public ArrayByteBufferPool(int[] bucketCapacities)
    {
        this(bucketCapacities, 0, 0);
    }

    public ArrayByteBufferPool(int[] bucketCapacities, long maxHeapMemory, long maxDirectMemory)
    {
        int[] capacities = sort(bucketCapacities.clone());
        _minCapacity = 0;
        _maxCapacity = capacities[capacities.length - 1];
        _maxHeapMemory = maxMemory(maxHeapMemory);
        _maxDirectMemory = maxMemory(maxDirectMemory);
        _direct = new RetainedBucket[capacities.length];
        _indirect = new RetainedBucket[capacities.length];
        for (int i = 0; i < capacities.length; ++i)
        {
            int capacity = capacities[i];
            _direct[i] = new RetainedBucket(capacity, Integer.MAX_VALUE);
            _indirect[i] = new RetainedBucket(capacity, Integer.MAX_VALUE);
        }
        _bucketIndexFor = bucketIndexFor(capacities);
        _bucketCapacityFor = bucketCapacityFor(capacities);
    }

    private static int[] sort(int... values)
    {
        if (values.length == 0)
            throw new IllegalArgumentException("At least one capacity is needed");
        Arrays.sort(values);
        if (values[0] <= 0)
            throw new IllegalArgumentException("Capacities must be positive: " + Arrays.toString(values));
        return values;
    }

    private static IntUnaryOperator bucketIndexFor(int[] capacities)
    {
        return capacity ->
        {
            int maxIdx = capacities.length - 1;
            int largestCapacity = capacities[maxIdx];
            if (capacity <= largestCapacity)
            {
                for (int i = 0; i <= maxIdx; i++)
                {
                    if (capacity <= capacities[i])
                        return i;
                }
                return maxIdx;
            }
            // A capacity over the largest configured capacity returns an
            // index that corresponds to where in the capacities array it
            // would stand if the latter had more entries that would all
            // be multiples of the largest configured capacity;
            // this logic is only meant for recordNoBucketAcquire().
            int remainder = (capacity % largestCapacity) != 0 ? 1 : 0;
            // The index of the virtual bucket, starting from maxIdx;
            // the first virtual bucket would have idx=1, and so on.
            int overMaxIdx = (capacity / largestCapacity) + remainder;
            return maxIdx + overMaxIdx - 1;
        };
    }

    private static IntUnaryOperator bucketCapacityFor(int[] capacities)
    {
        return idx ->
        {
            int maxIdx = capacities.length - 1;
            if (idx <= maxIdx)
                return capacities[idx];
            // An index over the capacities array's length is considered
            // to refer to a multiple of the largest configured capacity;
            // this logic is only meant for recordNoBucketAcquire().
            int largestCapacity = capacities[maxIdx];
            int overMaxIdx = idx - maxIdx + 1;
            return overMaxIdx * largestCapacity;
        };
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
            ByteBuffer byteBuffer = allocateByteBuffer(size, direct);
            return new PoolBuffer(byteBuffer, new Retainable.ReferenceCounter());
        }

        bucket.recordAcquire(size);

        // Try to acquire a pooled entry.
        Pool.Entry<RetainableByteBuffer.Mutable> entry = bucket.getPool().acquire();
        if (entry == null)
        {
            ByteBuffer byteBuffer = allocateByteBuffer(bucket.getCapacity(), direct);
            _reserved.addAndGet(byteBuffer.capacity());
            return new ReservedBuffer(byteBuffer, new Retainable.ReferenceCounter(), bucket);
        }

        bucket.recordPooled();
        PooledBuffer pooled = (PooledBuffer)entry.getPooled();
        pooled.acquire();
        return pooled;
    }

    private static ByteBuffer allocateByteBuffer(int size, boolean direct)
    {
        return direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size);
    }

    private void recordNoBucketAcquire(int size, boolean direct)
    {
        ConcurrentMap<Integer, NoBucketData> map = direct ? _noBucketDirectAcquires : _noBucketIndirectAcquires;
        int key;
        if (isStatisticsEnabled())
        {
            int idx = _bucketIndexFor.applyAsInt(size);
            key = _bucketCapacityFor.applyAsInt(idx);
        }
        else
        {
            key = 0;
        }
        map.compute(key, (_, v) ->
        {
            if (v == null)
                return new NoBucketData(1L, isStatisticsEnabled() ? new Throwable("Acquired by " + Thread.currentThread().getName()) : null);
            v.counter.incrementAndGet();
            return v;
        });
    }

    private void reserve(ByteBuffer byteBuffer, Retainable retainable, RetainedBucket bucket)
    {
        reset(byteBuffer);

        _reserved.addAndGet(-byteBuffer.capacity());
        bucket.recordRelease();

        // Try to reserve an entry to put the buffer into the pool.
        Pool.Entry<RetainableByteBuffer.Mutable> entry = bucket.getPool().reserve();
        if (entry == null)
        {
            bucket.recordNonPooled();
            return;
        }

        // Add the buffer to the new entry.
        RetainableByteBuffer.Mutable pooledBuffer = new PooledBuffer(byteBuffer, retainable, bucket, entry);
        if (entry.enable(pooledBuffer, false))
        {
            checkMaxMemory(bucket, byteBuffer.isDirect());
            return;
        }

        // Discard the buffer if the entry cannot be enabled.
        bucket.recordNonPooled();
        entry.remove();
    }

    private void release(RetainedBucket bucket, Pool.Entry<RetainableByteBuffer.Mutable> entry)
    {
        PooledBuffer buffer = (PooledBuffer)entry.getPooled();

        // The buffer may be half-read, clear it.
        buffer.clear();
        ByteBuffer byteBuffer = buffer.byteBuffer(false);
        reset(byteBuffer);

        bucket.recordRelease();

        if (entry.isTerminated())
            return;

        // Release the buffer and check the memory 1% of the times.
        int used = buffer.use();
        if (entry.release())
        {
            if (used % 100 == 0)
                checkMaxMemory(bucket, byteBuffer.isDirect());
            return;
        }

        // Cannot release, discard this buffer.
        bucket.recordRemove();
        entry.remove();
    }

    void reset(ByteBuffer byteBuffer)
    {
        byteBuffer.clear();
        byteBuffer.order(ByteOrder.BIG_ENDIAN);
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

    public Pool<RetainableByteBuffer.Mutable> poolFor(int capacity, boolean direct)
    {
        RetainedBucket bucket = bucketFor(capacity, direct);
        return bucket == null ? null : bucket.getPool();
    }

    private RetainedBucket bucketFor(int capacity, boolean direct)
    {
        if (capacity < getMinCapacity() || capacity > getMaxCapacity())
            return null;
        int idx = _bucketIndexFor.applyAsInt(capacity);
        RetainedBucket[] buckets = direct ? _direct : _indirect;
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
        {
            size += count.applyAsLong(bucket) * bucket.getCapacity();
        }
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
        ConcurrentMap<Integer, NoBucketData> map = direct ? _noBucketDirectAcquires : _noBucketIndirectAcquires;
        return map.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().counter.get()));
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
        return String.format("%s@%x{min=%d,max=%d,buckets=%d,heap=%d/%d,direct=%d/%d}",
            TypeUtil.toShortName(getClass()),
            hashCode(),
            _minCapacity, _maxCapacity,
            _direct.length,
            getHeapMemory(), _maxHeapMemory,
            getDirectMemory(), _maxDirectMemory);
    }

    private static class NoBucketData
    {
        private final AtomicLong counter;
        private final Throwable acquireStack;

        private NoBucketData(long counter, Throwable acquireStack)
        {
            this.counter = new AtomicLong(counter);
            this.acquireStack = acquireStack;
        }

        @Override
        public String toString()
        {
            if (acquireStack == null)
                return Long.toString(counter.get());
            StringWriter w = new StringWriter();
            PrintWriter pw = new PrintWriter(w);
            acquireStack.printStackTrace(pw);
            return counter + " from " + w;
        }
    }

    private class RetainedBucket implements Dumpable
    {
        private final LongAdder _acquires = new LongAdder();
        private final LongAdder _totalAcquired = new LongAdder();
        private final LongAdder _pooled = new LongAdder();
        private final LongAdder _nonPooled = new LongAdder();
        private final LongAdder _evicts = new LongAdder();
        private final LongAdder _removes = new LongAdder();
        private final LongAdder _releases = new LongAdder();
        private final Pool<RetainableByteBuffer.Mutable> _pool;
        private final int _capacity;

        private RetainedBucket(int capacity, int poolSize)
        {
            if (poolSize <= ConcurrentPool.OPTIMAL_MAX_SIZE)
                _pool = new ConcurrentPool<>(ConcurrentPool.StrategyType.THREAD_ID, poolSize, _ -> 1);
            else
                _pool = new BucketCompoundPool(
                    new ConcurrentPool<>(ConcurrentPool.StrategyType.THREAD_ID, ConcurrentPool.OPTIMAL_MAX_SIZE, _ -> 1),
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

        private Pool<RetainableByteBuffer.Mutable> getPool()
        {
            return _pool;
        }

        private int evict()
        {
            Pool.Entry<RetainableByteBuffer.Mutable> entry;
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
        public void dump(Appendable out, String indent) throws IOException
        {
            Dumpable.dumpObjects(out, indent, this, _pool);
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[%s]", TypeUtil.toShortName(this.getClass()), hashCode(), getStatistics());
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
                return String.format(Locale.ROOT, "capacity=%d,in-use=%d/%d,pooled/acquires/releases=%d/%d/%d(%.3f%%),avgSize=%d,non-pooled/evicts/removes=%d/%d/%d",
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

        private static class BucketCompoundPool extends CompoundPool<RetainableByteBuffer.Mutable>
        {
            private BucketCompoundPool(ConcurrentPool<RetainableByteBuffer.Mutable> concurrentBucket, QueuedPool<RetainableByteBuffer.Mutable> queuedBucket)
            {
                super(concurrentBucket, queuedBucket);
            }

            private Pool.Entry<RetainableByteBuffer.Mutable> evict()
            {
                Pool.Entry<RetainableByteBuffer.Mutable> entry = getSecondaryPool().acquire();
                if (entry == null)
                    entry = getPrimaryPool().acquire();
                return entry;
            }
        }
    }

    private static class PoolBuffer extends SingleMutableBuffer
    {
        private PoolBuffer(ByteBuffer byteBuffer, Retainable retainable)
        {
            super(byteBuffer, retainable, true, false);
        }

        @Override
        protected ByteBuffer byteBuffer(boolean readMode)
        {
            return super.byteBuffer(readMode);
        }
    }

    private class ReservedBuffer extends PoolBuffer
    {
        private final RetainedBucket _bucket;
        private final AtomicBoolean _removed = new AtomicBoolean();

        private ReservedBuffer(ByteBuffer byteBuffer, ReferenceCounter counter, RetainedBucket bucket)
        {
            super(byteBuffer, counter);
            _bucket = Objects.requireNonNull(bucket);
        }

        @Override
        public boolean release()
        {
            boolean released = super.release();
            if (released && _removed.compareAndSet(false, true))
                ArrayByteBufferPool.this.reserve(byteBuffer(false), retainable(), _bucket);
            return released;
        }
    }

    private class PooledBuffer extends PoolBuffer
    {
        private final RetainedBucket _bucket;
        private final Pool.Entry<RetainableByteBuffer.Mutable> _entry;
        private int _usages;

        private PooledBuffer(ByteBuffer byteBuffer, Retainable retainable, RetainedBucket bucket, Pool.Entry<RetainableByteBuffer.Mutable> entry)
        {
            super(byteBuffer, retainable);
            _bucket = Objects.requireNonNull(bucket);
            _entry = Objects.requireNonNull(entry);
        }

        void acquire()
        {
            ((ReferenceCounter)retainable()).acquire();
        }

        @Override
        public boolean release()
        {
            boolean released = super.release();
            if (released)
                ArrayByteBufferPool.this.release(_bucket, _entry);
            return released;
        }

        private int use()
        {
            if (++_usages < 0)
                _usages = 0;
            return _usages;
        }
    }

    /// A variant of [ArrayByteBufferPool] that tracks buffer
    /// acquires/releases, useful to identify buffer leaks.
    ///
    /// Use [#getLeaks()] when the system is idle to get
    /// the [TrackedBuffer]s that have been leaked, which contain
    /// the stack trace information of where the buffer was acquired/released.
    public static class Tracking extends ArrayByteBufferPool
    {
        private static final Logger LOG = LoggerFactory.getLogger(Tracking.class);
        private static final byte[] PADDING = new byte[1024];

        private final Set<TrackedBuffer> buffers = ConcurrentHashMap.newKeySet();

        public Tracking()
        {
            super();
        }

        public Tracking(int[] bucketCapacities)
        {
            super(bucketCapacities);
        }

        @Override
        public RetainableByteBuffer.Mutable acquire(int size, boolean direct)
        {
            PoolBuffer buffer = (PoolBuffer)super.acquire(size, direct);
            TrackedBuffer tracked = new TrackedBuffer(buffer.byteBuffer(false), buffer, size);
            if (LOG.isDebugEnabled())
                LOG.debug("acquired {}", tracked);
            buffers.add(tracked);
            return tracked;
        }

        @Override
        void reset(ByteBuffer byteBuffer)
        {
            super.reset(byteBuffer);
            // Zero-out the buffer to catch use-after-release.
            int position = 0;
            int capacity = byteBuffer.capacity();
            while (position < capacity)
            {
                int length = Math.min(capacity - position, PADDING.length);
                byteBuffer.put(position, PADDING, 0, length);
                position += length;
            }
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

        @Override
        public void dump(Appendable out, String indent) throws IOException
        {
            Dumpable.dumpObjects(
                out,
                indent,
                this,
                DumpableCollection.fromArray("direct", ((ArrayByteBufferPool)this)._direct),
                new DumpableMap("direct non-pooled acquisitions", ((ArrayByteBufferPool)this)._noBucketDirectAcquires),
                DumpableCollection.fromArray("indirect", ((ArrayByteBufferPool)this)._indirect),
                new DumpableMap("heap non-pooled acquisitions", ((ArrayByteBufferPool)this)._noBucketIndirectAcquires),
                DumpableCollection.from("leaks", getLeaks().stream().map(TrackedBuffer::dump).toList())
            );
        }

        public class TrackedBuffer extends SingleMutableBuffer
        {
            private final int size;
            private final Instant acquireInstant;
            private final Throwable acquireStack;
            private final List<Throwable> retainStacks = new CopyOnWriteArrayList<>();
            private final List<Throwable> releaseStacks = new CopyOnWriteArrayList<>();
            private final List<Throwable> overReleaseStacks = new CopyOnWriteArrayList<>();

            private TrackedBuffer(ByteBuffer byteBuffer, Retainable retainable, int size)
            {
                super(byteBuffer, retainable, true, false);
                this.size = size;
                this.acquireInstant = Instant.now();
                this.acquireStack = new Throwable("acquired by " + Thread.currentThread().getName());
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
                retainStacks.add(new Throwable("retained by " + Thread.currentThread().getName()));
            }

            @Override
            public boolean release()
            {
                boolean released;
                try
                {
                    released = super.release();
                }
                catch (IllegalStateException e)
                {
                    buffers.add(this);
                    overReleaseStacks.add(new Throwable("over-released by " + Thread.currentThread().getName()));
                    IllegalStateException ise = new IllegalStateException(Thread.currentThread().getName() + " over-released " + this);
                    releaseStacks.forEach(ise::addSuppressed);
                    throw ise;
                }
                if (released)
                {
                    buffers.remove(this);
                    if (LOG.isDebugEnabled())
                        LOG.debug("released {}", this);
                }
                releaseStacks.add(new Throwable("released by " + Thread.currentThread().getName()));
                return released;
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
                String stacks = w.toString();
                return ("%s@%x of %d bytes on %s wrapping %s%n" +
                    " %s%n" +
                    " acquired at %s")
                    .formatted(TypeUtil.toShortName(getClass()), hashCode(), getSize(), getAcquireInstant(), retainable(),
                        this,
                        stacks);
            }
        }
    }
}
