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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BufferAggregatorTest
{
    private final ArrayByteBufferPool.Tracking bufferPool = new ArrayByteBufferPool.Tracking();

    @AfterEach
    public void tearDown()
    {
        assertEquals(0, bufferPool.getLeaks().size(), bufferPool.dumpLeaks());
    }

    private static RetainableByteBuffer wrap(String string)
    {
        return RetainableByteBuffer.wrap(string, UTF_8);
    }

    private static String take(BufferAggregator aggregator)
    {
        try (RetainableByteBuffer taken = aggregator.take())
        {
            return taken.getString(UTF_8);
        }
    }

    @Test
    public void testNothingAggregated()
    {
        try (BufferAggregator aggregator = new BufferAggregator(bufferPool, false, true, 16))
        {
            assertFalse(aggregator.hasRemaining());
            assertEquals(0, aggregator.remaining());
            assertEquals("", aggregator.getString(UTF_8));
            assertEquals("", take(aggregator));
        }
    }

    @Test
    public void testAppendConsumesData()
    {
        try (BufferAggregator aggregator = new BufferAggregator(bufferPool, false, true, 16))
        {
            RetainableByteBuffer hello = wrap("hello");
            assertEquals(5, aggregator.append(hello));
            assertFalse(hello.hasRemaining());
            RetainableByteBuffer world = wrap(" world");
            assertEquals(6, aggregator.append(world));
            assertFalse(world.hasRemaining());

            assertTrue(aggregator.hasRemaining());
            assertEquals(11, aggregator.remaining());
            assertEquals("hello world", take(aggregator));
        }
    }

    @Test
    public void testAppendEmptyData()
    {
        try (BufferAggregator aggregator = new BufferAggregator(bufferPool, false, true, 16))
        {
            assertEquals(0, aggregator.append(wrap("")));
            assertFalse(aggregator.hasRemaining());
            assertEquals("", take(aggregator));
        }
    }

    @Test
    public void testDataIsCopied()
    {
        try (BufferAggregator aggregator = new BufferAggregator(bufferPool, false, true, 16))
        {
            try (RetainableByteBuffer.Mutable data = bufferPool.acquire(16, false))
            {
                data.put("hello".getBytes(UTF_8));
                aggregator.append(data);
                data.clear();
                data.put("XXXXX".getBytes(UTF_8));
            }
            assertEquals("hello", aggregator.getString(UTF_8));
        }
    }

    @Test
    public void testGetStringDoesNotModify()
    {
        try (BufferAggregator aggregator = new BufferAggregator(bufferPool, false, true, 16))
        {
            aggregator.append(wrap("hello"));
            assertEquals("hello", aggregator.getString(UTF_8));
            assertEquals("hello", aggregator.getString(UTF_8));
            assertEquals(5, aggregator.remaining());

            aggregator.append(wrap(" world"));
            assertEquals("hello world", aggregator.getString(UTF_8));
            assertEquals("hello world", take(aggregator));
        }
    }

    @Test
    public void testTakeLeavesAggregatorEmptyAndReusable()
    {
        try (BufferAggregator aggregator = new BufferAggregator(bufferPool, false, true, 16))
        {
            aggregator.append(wrap("first"));
            try (RetainableByteBuffer first = aggregator.take())
            {
                assertFalse(aggregator.hasRemaining());
                assertEquals(0, aggregator.remaining());
                assertEquals("", aggregator.getString(UTF_8));

                // The taken buffer is independent of later appends.
                aggregator.append(wrap("second"));
                assertEquals("first", first.getString(UTF_8));
            }
            assertEquals("second", take(aggregator));
        }
    }

    @Test
    public void testTakeRestartsFromInitialCapacity()
    {
        List<Integer> sizes = new ArrayList<>();
        ByteBufferPool recordingPool = (size, direct) ->
        {
            sizes.add(size);
            return ByteBufferPool.NON_POOLING.acquire(size, direct);
        };
        try (BufferAggregator aggregator = new BufferAggregator(recordingPool, false, true, 4))
        {
            aggregator.append(wrap("0123456789"));
            assertEquals("0123456789", take(aggregator));
            aggregator.append(wrap("0123"));
            assertEquals("0123", take(aggregator));
        }
        assertEquals(List.of(4, 10, 4), sizes);
    }

    @Test
    public void testClear()
    {
        try (BufferAggregator aggregator = new BufferAggregator(bufferPool, false, true, 16))
        {
            aggregator.clear();
            assertFalse(aggregator.hasRemaining());

            aggregator.append(wrap("hello"));
            aggregator.clear();
            assertFalse(aggregator.hasRemaining());
            assertEquals(0, aggregator.remaining());
            assertEquals("", aggregator.getString(UTF_8));

            aggregator.append(wrap("world"));
            assertEquals("world", take(aggregator));
        }
    }

    @Test
    public void testClearAllowsToAppendUpToMaxCapacityAgain()
    {
        try (BufferAggregator aggregator = new BufferAggregator(bufferPool, false, true, 4, 4))
        {
            assertEquals(4, aggregator.append(wrap("01234")));
            aggregator.clear();
            assertEquals(4, aggregator.append(wrap("abcde")));
            assertEquals("abcd", take(aggregator));
        }
    }

    @Test
    public void testCloseReleasesBackingStore()
    {
        BufferAggregator aggregator = new BufferAggregator(bufferPool, false, true, 16);
        aggregator.append(wrap("hello"));
        aggregator.close();
        assertFalse(aggregator.hasRemaining());
        // Closing again is a no-op; the leak check in tearDown() verifies the release.
        aggregator.close();
    }

    @Test
    public void testCloseDoesNotReleaseTakenBuffer()
    {
        RetainableByteBuffer taken;
        try (BufferAggregator aggregator = new BufferAggregator(bufferPool, false, true, 16))
        {
            aggregator.append(wrap("hello"));
            taken = aggregator.take();
        }
        assertEquals("hello", taken.getString(UTF_8));
        assertTrue(taken.release());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4, 16})
    public void testGrowsWithoutMaxCapacity(int initialCapacity)
    {
        String content = "x".repeat(16 * 1024);
        // Growing must release the replaced backing stores, see tearDown().
        try (BufferAggregator aggregator = new BufferAggregator(bufferPool, false, true, initialCapacity))
        {
            assertEquals(content.length(), aggregator.append(wrap(content)));
            assertEquals(content.length(), aggregator.remaining());
            assertEquals(content, take(aggregator));
        }
    }

    @Test
    public void testGrowsWithoutMaxCapacityWithNullPool()
    {
        try (BufferAggregator aggregator = new BufferAggregator(true, 4))
        {
            assertEquals(5, aggregator.append(wrap("01234")));
            assertEquals(5, aggregator.append(wrap("56789")));
            assertEquals("0123456789", take(aggregator));
        }
    }

    @Test
    public void testGrowPreservesContent()
    {
        try (BufferAggregator aggregator = new BufferAggregator(bufferPool, false, true, 4))
        {
            StringBuilder expected = new StringBuilder();
            for (int i = 0; i < 8192; ++i)
            {
                String piece = String.valueOf(i % 10);
                expected.append(piece);
                assertEquals(1, aggregator.append(wrap(piece)));
            }
            assertEquals(expected.toString(), take(aggregator));
        }
    }

    @Test
    public void testGrowthSizes()
    {
        List<Integer> sizes = new ArrayList<>();
        ByteBufferPool recordingPool = (size, direct) ->
        {
            sizes.add(size);
            return ByteBufferPool.NON_POOLING.acquire(size, direct);
        };
        try (BufferAggregator aggregator = new BufferAggregator(recordingPool, false, true, 100))
        {
            // The space is enough, no growth.
            aggregator.append(wrap("x".repeat(100)));
            assertEquals(List.of(100), sizes);
            // Grows by half of the capacity, if that is enough.
            aggregator.append(wrap("x"));
            assertEquals(List.of(100, 150), sizes);
            // Grows to what is necessary, if that is larger.
            aggregator.append(wrap("x".repeat(1000)));
            assertEquals(List.of(100, 150, 1101), sizes);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testDirectIsHonored(boolean direct)
    {
        List<Boolean> directs = new ArrayList<>();
        ByteBufferPool recordingPool = (size, d) ->
        {
            directs.add(d);
            return ByteBufferPool.NON_POOLING.acquire(size, d);
        };
        try (BufferAggregator aggregator = new BufferAggregator(recordingPool, direct, true, 4))
        {
            aggregator.append(wrap("0123456789"));
            assertEquals("0123456789", take(aggregator));
        }
        assertEquals(List.of(direct, direct), directs);
    }

    @Test
    public void testNotGrowableAppendsWhatFits()
    {
        try (BufferAggregator aggregator = new BufferAggregator(false, 8))
        {
            RetainableByteBuffer data = wrap("0123456789");
            assertEquals(8, aggregator.append(data));
            assertEquals(2, data.remaining());

            // Full, the bytes that do not fit stay in the data.
            assertEquals(0, aggregator.append(data));
            assertEquals(2, data.remaining());
            assertEquals("01234567", take(aggregator));

            // The bytes that did not fit can be appended after a take().
            assertEquals(2, aggregator.append(data));
            assertFalse(data.hasRemaining());
            assertEquals("89", take(aggregator));
        }
    }

    @Test
    public void testNotGrowableExactFit()
    {
        try (BufferAggregator aggregator = new BufferAggregator(false, 4))
        {
            RetainableByteBuffer data = wrap("0123");
            assertEquals(4, aggregator.append(data));
            assertFalse(data.hasRemaining());
            assertEquals("0123", take(aggregator));
        }
    }

    @Test
    public void testNotGrowableIgnoresLargerMaxCapacity()
    {
        // Must not lose the bytes between the capacity and the max capacity.
        try (BufferAggregator aggregator = new BufferAggregator(null, false, false, 4, 8))
        {
            RetainableByteBuffer data = wrap("0123456789");
            assertEquals(4, aggregator.append(data));
            assertEquals(6, data.remaining());
            assertEquals("0123", take(aggregator));
            assertEquals("456789", data.getString(UTF_8));
        }
    }

    @Test
    public void testNotGrowableWithMaxCapacitySmallerThanPooledCapacity()
    {
        try (BufferAggregator aggregator = new BufferAggregator(bufferPool, false, false, 1024, 1024))
        {
            RetainableByteBuffer data = wrap("x".repeat(1500));
            assertEquals(1024, aggregator.append(data));
            assertEquals(476, data.remaining());
            assertEquals(1024, aggregator.remaining());
        }
    }

    @Test
    public void testGrowsUpToMaxCapacity()
    {
        List<Integer> sizes = new ArrayList<>();
        ByteBufferPool recordingPool = (size, direct) ->
        {
            sizes.add(size);
            return ByteBufferPool.NON_POOLING.acquire(size, direct);
        };
        try (BufferAggregator aggregator = new BufferAggregator(recordingPool, false, true, 4, 10))
        {
            RetainableByteBuffer data = wrap("0123456789ABCDE");
            assertEquals(10, aggregator.append(data));
            assertEquals(5, data.remaining());
            assertEquals(10, aggregator.remaining());
            // The growth request must be limited to the max capacity.
            assertEquals(List.of(4, 10), sizes);

            // Full, the bytes that do not fit stay in the data, and there is no more growth.
            assertEquals(0, aggregator.append(data));
            assertEquals(5, data.remaining());
            assertEquals(List.of(4, 10), sizes);

            assertEquals("0123456789", take(aggregator));
            assertEquals(5, aggregator.append(data));
            assertFalse(data.hasRemaining());
            assertEquals("ABCDE", take(aggregator));
        }
    }

    @Test
    public void testMaxCapacityExactFit()
    {
        try (BufferAggregator aggregator = new BufferAggregator(null, false, true, 4, 10))
        {
            RetainableByteBuffer data = wrap("0123456789");
            assertEquals(10, aggregator.append(data));
            assertFalse(data.hasRemaining());
            assertEquals("0123456789", take(aggregator));
        }
    }

    @Test
    public void testMaxCapacityEqualToInitialCapacity()
    {
        try (BufferAggregator aggregator = new BufferAggregator(null, false, true, 4, 4))
        {
            RetainableByteBuffer data = wrap("012345");
            assertEquals(4, aggregator.append(data));
            assertEquals(2, data.remaining());
            assertEquals("0123", take(aggregator));
        }
    }

    @Test
    public void testZeroMaxCapacity()
    {
        try (BufferAggregator aggregator = new BufferAggregator(null, false, true, 0, 0))
        {
            RetainableByteBuffer data = wrap("0123");
            assertEquals(0, aggregator.append(data));
            assertEquals(4, data.remaining());
            assertFalse(aggregator.hasRemaining());
            assertEquals("", take(aggregator));
        }
    }

    @Test
    public void testInitialCapacityLargerThanMaxCapacity()
    {
        assertThrows(IllegalArgumentException.class, () -> new BufferAggregator(null, false, true, 8, 4));
    }

    @Test
    public void testMaxCapacityIsNotExceededWhenPoolRoundsUpCapacity()
    {
        try (BufferAggregator aggregator = new BufferAggregator(bufferPool, false, true, 1024, 1024))
        {
            RetainableByteBuffer data = wrap("x".repeat(1500));
            assertEquals(1024, aggregator.append(data));
            assertEquals(476, data.remaining());
            try (RetainableByteBuffer taken = aggregator.take())
            {
                // The precondition of this test is that the pool rounded up.
                assertThat(taken.capacity(), greaterThan(1024L));
                assertEquals(1024, taken.remaining());
            }
        }
    }

    @Test
    public void testMaxCapacityIsNotExceededByManyAppendsWhenPoolRoundsUpCapacity()
    {
        try (BufferAggregator aggregator = new BufferAggregator(bufferPool, false, true, 1024, 1024))
        {
            for (int i = 0; i < 1024; ++i)
            {
                assertEquals(1, aggregator.append(wrap("x")));
            }
            assertEquals(0, aggregator.append(wrap("x")));
            assertEquals(1024, aggregator.remaining());
        }
    }

    @Test
    public void testMaxCapacityIsNotExceededWhenGrownBufferIsRoundedUp()
    {
        try (BufferAggregator aggregator = new BufferAggregator(bufferPool, false, true, 1024, 3000))
        {
            RetainableByteBuffer data = wrap("x".repeat(5000));
            assertEquals(3000, aggregator.append(data));
            assertEquals(2000, data.remaining());
            try (RetainableByteBuffer taken = aggregator.take())
            {
                assertThat(taken.capacity(), greaterThan(3000L));
                assertEquals(3000, taken.remaining());
            }
        }
    }

    @Test
    public void testPartialAppendReleasesInternalSlice()
    {
        // Slicing a pooled data buffer retains it; if the slice was not
        // released, then the release below would not return it to the pool.
        RetainableByteBuffer.Mutable data = bufferPool.acquire(64, false);
        data.put(new byte[64]);
        try (BufferAggregator aggregator = new BufferAggregator(bufferPool, false, true, 16, 16))
        {
            assertEquals(16, aggregator.append(data));
            assertEquals(48, data.remaining());
        }
        assertFalse(data.isRetained());
        assertTrue(data.release());
    }

    @Test
    public void testAppendAfterFullDoesNotReplaceBackingStore()
    {
        List<RetainableByteBuffer.Mutable> buffers = new ArrayList<>();
        ByteBufferPool recordingPool = (size, direct) ->
        {
            RetainableByteBuffer.Mutable buffer = ByteBufferPool.NON_POOLING.acquire(size, direct);
            buffers.add(buffer);
            return buffer;
        };
        try (BufferAggregator aggregator = new BufferAggregator(recordingPool, false, true, 8, 8))
        {
            aggregator.append(wrap("01234567"));
            assertEquals(0, aggregator.append(wrap("8")));
            assertEquals(1, buffers.size());
            try (RetainableByteBuffer taken = aggregator.take())
            {
                assertSame(buffers.getFirst(), taken);
            }
        }
    }
}
