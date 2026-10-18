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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BufferAccumulatorTest
{
    private final ArrayByteBufferPool.Tracking bufferPool = new ArrayByteBufferPool.Tracking();

    @AfterEach
    public void tearDown()
    {
        assertEquals(0, bufferPool.getLeaks().size(), bufferPool.dumpLeaks());
    }

    private RetainableByteBuffer.Mutable pooled(String content)
    {
        RetainableByteBuffer.Mutable buffer = bufferPool.acquire(Math.max(1, content.length()), false);
        buffer.put(content.getBytes(UTF_8));
        return buffer;
    }

    private static String take(BufferAccumulator accumulator)
    {
        try (RetainableByteBuffer taken = accumulator.take())
        {
            return taken.getString(UTF_8);
        }
    }

    @Test
    public void testNothingAccumulated()
    {
        BufferAccumulator accumulator = new BufferAccumulator();
        assertFalse(accumulator.hasRemaining());
        assertEquals(0, accumulator.remaining());
        try (RetainableByteBuffer taken = accumulator.take())
        {
            assertFalse(taken.hasRemaining());
            assertEquals(0, taken.remaining());
        }
    }

    @Test
    public void testAddRetainsBuffer()
    {
        RetainableByteBuffer.Mutable buffer = pooled("hello");
        BufferAccumulator accumulator = new BufferAccumulator();
        assertFalse(buffer.isRetained());

        accumulator.add(buffer);
        assertTrue(buffer.isRetained());

        // The accumulator owns its own reference, so the
        // buffer is not recycled when the caller releases it.
        assertFalse(buffer.release());
        assertEquals(5, accumulator.remaining());
        assertEquals("hello", take(accumulator));
    }

    @Test
    public void testAddRetainedDoesNotRetainBuffer()
    {
        RetainableByteBuffer.Mutable buffer = pooled("hello");
        BufferAccumulator accumulator = new BufferAccumulator();

        accumulator.addRetained(buffer);
        assertFalse(buffer.isRetained());

        // The accumulator owns the only reference,
        // which is released with the taken buffer.
        assertEquals("hello", take(accumulator));
    }

    @Test
    public void testAddNullThrows()
    {
        BufferAccumulator accumulator = new BufferAccumulator();
        assertThrows(NullPointerException.class, () -> accumulator.add(null));
        assertFalse(accumulator.hasRemaining());
    }

    @Test
    public void testAddRetainedNullThrows()
    {
        BufferAccumulator accumulator = new BufferAccumulator();
        assertThrows(NullPointerException.class, () -> accumulator.addRetained(null));
        assertFalse(accumulator.hasRemaining());
        accumulator.clear();
    }

    @Test
    public void testAddDuplicateThrowsAndDoesNotRetain()
    {
        RetainableByteBuffer.Mutable buffer = pooled("hello");
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.add(buffer);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> accumulator.add(buffer));
        assertTrue(failure.getMessage().startsWith("duplicate non-empty buffer"), failure.getMessage());

        // The failed add() must not have retained the buffer: there is
        // only the reference of this test and that of the accumulator.
        assertEquals(5, accumulator.remaining());
        assertFalse(buffer.release());
        assertEquals("hello", take(accumulator));
    }

    @Test
    public void testAddRetainedDuplicateThrowsAndLeavesReferenceToCaller()
    {
        RetainableByteBuffer.Mutable buffer = pooled("hello");
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(buffer);
        buffer.retain();

        assertThrows(IllegalArgumentException.class, () -> accumulator.addRetained(buffer));

        // The accumulator did not take ownership of the second reference.
        assertFalse(buffer.release());
        assertEquals("hello", take(accumulator));
    }

    @Test
    public void testMixedAddAndAddRetainedDuplicateThrows()
    {
        RetainableByteBuffer.Mutable buffer = pooled("hello");
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(buffer);

        assertThrows(IllegalArgumentException.class, () -> accumulator.add(buffer));

        assertEquals("hello", take(accumulator));
    }

    @Test
    public void testDuplicateThrowsAmongManyBuffers()
    {
        RetainableByteBuffer.Mutable buffer = pooled("hello");
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(pooled("first"));
        accumulator.addRetained(buffer);
        accumulator.addRetained(pooled("last"));

        assertThrows(IllegalArgumentException.class, () -> accumulator.add(buffer));

        assertEquals("firsthellolast", take(accumulator));
    }

    @Test
    public void testDuplicateDoesNotThrowIfConsumedInTheMeanTime()
    {
        RetainableByteBuffer.Mutable buffer = pooled("hello");
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(buffer);
        buffer.consume(5);

        accumulator.add(buffer);
        assertTrue(buffer.isRetained());
        assertFalse(accumulator.hasRemaining());
        assertEquals(0, accumulator.remaining());

        // Each addition holds a reference that is released by clear().
        accumulator.clear();
        assertEquals(0, bufferPool.getLeaks().size(), bufferPool.dumpLeaks());
    }

    @Test
    public void testDuplicateEmptyPooledBufferDoesNotThrow()
    {
        RetainableByteBuffer.Mutable buffer = pooled("");
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(buffer);

        accumulator.add(buffer);
        assertTrue(buffer.isRetained());
        assertFalse(accumulator.hasRemaining());

        // Each addition holds a reference that is released by clear().
        accumulator.clear();
        assertEquals(0, bufferPool.getLeaks().size(), bufferPool.dumpLeaks());
    }

    @Test
    public void testTakeDuplicateEmptyPooledBufferReleasesReferences()
    {
        RetainableByteBuffer.Mutable buffer = pooled("");
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(buffer);
        accumulator.add(buffer);

        try (RetainableByteBuffer taken = accumulator.take())
        {
            assertFalse(taken.hasRemaining());
        }
        assertEquals(0, bufferPool.getLeaks().size(), bufferPool.dumpLeaks());
    }

    @Test
    public void testSameBufferCanBeAddedAgainAfterTake()
    {
        RetainableByteBuffer.Mutable buffer = pooled("hello");
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.add(buffer);
        try (RetainableByteBuffer taken = accumulator.take())
        {
            assertEquals(5, taken.remaining());
        }

        accumulator.add(buffer);
        assertEquals(5, accumulator.remaining());

        accumulator.clear();
        assertTrue(buffer.release());
    }

    @Test
    public void testSameBufferCanBeAddedAgainAfterClear()
    {
        RetainableByteBuffer.Mutable buffer = pooled("hello");
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.add(buffer);
        accumulator.clear();

        accumulator.add(buffer);
        assertEquals(5, accumulator.remaining());

        accumulator.clear();
        assertTrue(buffer.release());
    }

    @Test
    public void testSharedEmptyBufferCanBeAddedManyTimes()
    {
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(RetainableByteBuffer.empty());
        accumulator.addRetained(RetainableByteBuffer.empty());
        accumulator.add(RetainableByteBuffer.empty());
        assertFalse(accumulator.hasRemaining());
        assertEquals(0, accumulator.remaining());

        accumulator.addRetained(pooled("hello"));
        accumulator.addRetained(RetainableByteBuffer.empty());
        assertEquals("hello", take(accumulator));
    }

    @Test
    public void testSlicesOfSameBufferAreNotDuplicates()
    {
        RetainableByteBuffer.Mutable buffer = pooled("hello");
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(buffer.slice());
        accumulator.addRetained(buffer.slice());

        assertEquals(10, accumulator.remaining());
        assertEquals("hellohello", take(accumulator));

        assertEquals(5, buffer.remaining());
        assertTrue(buffer.release());
    }

    @Test
    public void testHasRemainingAndRemainingAcrossBuffers()
    {
        BufferAccumulator accumulator = new BufferAccumulator();

        accumulator.addRetained(pooled(""));
        assertFalse(accumulator.hasRemaining());
        assertEquals(0, accumulator.remaining());

        accumulator.addRetained(pooled("01234"));
        assertTrue(accumulator.hasRemaining());
        assertEquals(5, accumulator.remaining());

        accumulator.addRetained(pooled(""));
        accumulator.addRetained(pooled("567"));
        assertTrue(accumulator.hasRemaining());
        assertEquals(8, accumulator.remaining());

        accumulator.clear();
    }

    @Test
    public void testAddDoesNotConsumeBuffer()
    {
        RetainableByteBuffer.Mutable buffer = pooled("hello");
        BufferAccumulator accumulator = new BufferAccumulator();

        accumulator.add(buffer);

        assertEquals(5, buffer.remaining());
        accumulator.clear();
        assertEquals(5, buffer.remaining());
        assertTrue(buffer.release());
    }

    @Test
    public void testBytesAreNotCopiedSoAccumulatorSeesConsumption()
    {
        RetainableByteBuffer.Mutable buffer = pooled("hello");
        BufferAccumulator accumulator = new BufferAccumulator();

        accumulator.add(buffer);
        buffer.consume(2);
        assertEquals(3, accumulator.remaining());

        buffer.consume(3);
        assertFalse(accumulator.hasRemaining());
        assertEquals(0, accumulator.remaining());

        accumulator.clear();
        assertTrue(buffer.release());
    }

    @Test
    public void testPartiallyConsumedBuffersContributeOnlyRemainingBytes()
    {
        RetainableByteBuffer.Mutable buffer1 = pooled("01234");
        buffer1.consume(3);
        RetainableByteBuffer.Mutable buffer2 = pooled("56789");
        buffer2.consume(1);

        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(buffer1);
        accumulator.addRetained(buffer2);

        assertEquals(6, accumulator.remaining());
        assertEquals("346789", take(accumulator));
    }

    @Test
    public void testTakeSingleBufferReturnsSameBufferWithoutConsumingIt()
    {
        RetainableByteBuffer.Mutable buffer = pooled("hello");
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(buffer);

        RetainableByteBuffer taken = accumulator.take();

        assertSame(buffer, taken);
        assertEquals(5, taken.remaining());
        assertFalse(accumulator.hasRemaining());
        assertTrue(taken.release());
    }

    @Test
    public void testTakeSingleEmptyBufferMustBeReleased()
    {
        RetainableByteBuffer.Mutable buffer = pooled("");
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(buffer);

        RetainableByteBuffer taken = accumulator.take();

        assertSame(buffer, taken);
        assertFalse(taken.hasRemaining());
        assertTrue(taken.release());
    }

    @Test
    public void testTakeMultipleBuffersPreservesOrder()
    {
        BufferAccumulator accumulator = new BufferAccumulator();
        StringBuilder expected = new StringBuilder();
        for (int i = 0; i < 64; ++i)
        {
            String content = "buffer" + i + ";";
            expected.append(content);
            accumulator.addRetained(pooled(content));
        }

        assertEquals(expected.length(), accumulator.remaining());
        assertEquals(expected.toString(), take(accumulator));
    }

    @Test
    public void testTakeMultipleBuffersCanBeReadAcrossBoundaries()
    {
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(pooled("012"));
        accumulator.addRetained(pooled("345"));
        accumulator.addRetained(pooled("678"));

        try (RetainableByteBuffer taken = accumulator.take())
        {
            assertEquals(9, taken.remaining());
            byte[] bytes = new byte[5];
            taken.get(bytes);
            assertEquals("01234", new String(bytes, UTF_8));
            assertEquals(4, taken.remaining());
            assertEquals('5', taken.get());
            assertEquals("678", taken.getString(UTF_8));
        }
    }

    @Test
    public void testTakeLeavesAccumulatorEmptyAndReusable()
    {
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(pooled("first1"));
        accumulator.addRetained(pooled("first2"));

        try (RetainableByteBuffer first = accumulator.take())
        {
            assertFalse(accumulator.hasRemaining());
            assertEquals(0, accumulator.remaining());

            // A second take() has nothing to return.
            try (RetainableByteBuffer empty = accumulator.take())
            {
                assertFalse(empty.hasRemaining());
            }

            // The taken buffer is independent of later additions.
            accumulator.addRetained(pooled("second"));
            assertEquals("first1first2", first.getString(UTF_8));
        }
        assertEquals("second", take(accumulator));
    }

    @Test
    public void testTakeMultipleBuffersConsumesAddedBuffers()
    {
        RetainableByteBuffer.Mutable buffer1 = pooled("hello");
        RetainableByteBuffer.Mutable buffer2 = pooled("world");
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.add(buffer1);
        accumulator.add(buffer2);

        try (RetainableByteBuffer taken = accumulator.take())
        {
            // Differently from the single buffer case, the added
            // buffers are sliced into the merged one and consumed.
            assertFalse(buffer1.hasRemaining());
            assertFalse(buffer2.hasRemaining());
            assertEquals(10, taken.remaining());
        }

        assertTrue(buffer1.release());
        assertTrue(buffer2.release());
    }

    @Test
    public void testTakenBufferKeepsAddedBuffersFromBeingRecycled()
    {
        RetainableByteBuffer.Mutable buffer1 = pooled("hello");
        RetainableByteBuffer.Mutable buffer2 = pooled("world");
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(buffer1);
        accumulator.addRetained(buffer2);

        RetainableByteBuffer taken = accumulator.take();

        // The bytes are still in the added buffers, which must not be recycled yet.
        assertFalse(buffer1.isRetained());
        assertFalse(buffer2.isRetained());
        assertEquals(2, bufferPool.getLeaks().size());
        assertEquals("helloworld", taken.getString(UTF_8));

        // Releasing the taken buffer releases the added buffers.
        assertTrue(taken.release());
    }

    @Test
    public void testTakenBufferCanOutliveCallerReferences()
    {
        RetainableByteBuffer.Mutable buffer1 = pooled("hello");
        RetainableByteBuffer.Mutable buffer2 = pooled("world");
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.add(buffer1);
        accumulator.add(buffer2);

        try (RetainableByteBuffer taken = accumulator.take())
        {
            assertTrue(buffer1.isRetained());
            assertFalse(buffer1.release());
            assertFalse(buffer2.release());
            assertEquals("helloworld", taken.getString(UTF_8));
        }
    }

    @Test
    public void testTakeSkipsEmptyBuffers()
    {
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(pooled(""));
        accumulator.addRetained(pooled("01"));
        accumulator.addRetained(pooled(""));
        accumulator.addRetained(pooled("23"));
        accumulator.addRetained(pooled(""));

        assertEquals("0123", take(accumulator));
    }

    @Test
    public void testTakeMultipleBuffersOnlyOneNotEmpty()
    {
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(pooled(""));
        accumulator.addRetained(pooled("hello"));
        accumulator.addRetained(pooled(""));

        assertEquals("hello", take(accumulator));
    }

    @Test
    public void testTakeMultipleBuffersAllEmpty()
    {
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(pooled(""));
        accumulator.addRetained(pooled(""));

        try (RetainableByteBuffer taken = accumulator.take())
        {
            assertFalse(taken.hasRemaining());
        }
    }

    @Test
    public void testAddAlreadyMergedBufferAsSingleBuffer()
    {
        RetainableByteBuffer.Mutable buffer1 = pooled("01");
        RetainableByteBuffer.Mutable buffer2 = pooled("23");
        RetainableByteBuffer merged = RetainableByteBuffer.merge(buffer1, buffer2);
        // The merge above retains the slices of these
        // buffers, release the references of this test.
        assertFalse(buffer1.release());
        assertFalse(buffer2.release());

        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(merged);

        RetainableByteBuffer taken = accumulator.take();

        assertSame(merged, taken);
        assertEquals("0123", taken.getString(UTF_8));
        assertTrue(taken.release());
    }

    @Test
    public void testAddAlreadyMergedBufferIsFlattened()
    {
        RetainableByteBuffer.Mutable buffer1 = pooled("01");
        RetainableByteBuffer.Mutable buffer2 = pooled("23");
        RetainableByteBuffer merged = RetainableByteBuffer.merge(buffer1, buffer2);
        // The merge above retains the slices of these
        // buffers, release the references of this test.
        assertFalse(buffer1.release());
        assertFalse(buffer2.release());

        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.add(merged);
        accumulator.addRetained(pooled("45"));

        assertEquals(6, accumulator.remaining());
        assertEquals("012345", take(accumulator));
        assertTrue(merged.release());
    }

    @Test
    public void testAddSliceKeepsParentBufferRetained()
    {
        RetainableByteBuffer.Mutable buffer = pooled("0123456789");
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(buffer.sliceAndConsume(4));
        accumulator.addRetained(buffer.sliceAndConsume(4));
        assertEquals(2, buffer.remaining());

        assertEquals("01234567", take(accumulator));

        // The slices have been released with the taken buffer.
        assertFalse(buffer.isRetained());
        assertTrue(buffer.release());
    }

    @Test
    public void testClearWhenEmpty()
    {
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.clear();
        assertFalse(accumulator.hasRemaining());
    }

    @Test
    public void testClearReleasesAccumulatedBuffers()
    {
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(pooled("hello"));
        accumulator.addRetained(pooled("world"));
        assertEquals(2, bufferPool.getLeaks().size());

        accumulator.clear();

        assertFalse(accumulator.hasRemaining());
        assertEquals(0, accumulator.remaining());
        assertEquals(0, bufferPool.getLeaks().size());
    }

    @Test
    public void testClearReleasesOnlyAccumulatorReference()
    {
        RetainableByteBuffer.Mutable buffer = pooled("hello");
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.add(buffer);

        accumulator.clear();

        // The caller reference is still valid.
        assertFalse(buffer.isRetained());
        assertEquals("hello", buffer.getString(UTF_8));
        assertTrue(buffer.release());
    }

    @Test
    public void testClearTwiceDoesNotOverRelease()
    {
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(pooled("hello"));
        accumulator.clear();
        accumulator.clear();
    }

    @Test
    public void testAccumulatorIsReusableAfterClear()
    {
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(pooled("hello"));
        accumulator.clear();

        accumulator.addRetained(pooled("world"));
        assertEquals(5, accumulator.remaining());
        assertEquals("world", take(accumulator));
    }

    @Test
    public void testClearAfterTakeDoesNotReleaseTakenBuffer()
    {
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.addRetained(pooled("hello"));
        accumulator.addRetained(pooled("world"));

        try (RetainableByteBuffer taken = accumulator.take())
        {
            accumulator.clear();
            assertEquals("helloworld", taken.getString(UTF_8));
        }
    }

    @Test
    public void testAddNonPooledBuffers()
    {
        BufferAccumulator accumulator = new BufferAccumulator();
        accumulator.add(RetainableByteBuffer.wrap("hello", UTF_8));
        accumulator.addRetained(RetainableByteBuffer.wrap(" world", UTF_8));

        assertEquals(11, accumulator.remaining());
        assertEquals("hello world", take(accumulator));
    }
}
