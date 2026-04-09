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

package org.eclipse.jetty.util.internal;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AccumulatingReadBufferTest
{
    @Test
    public void testEmptyListIsEmptyInstance()
    {
        ReadableBuffer rb = ReadableBuffer.accumulate(List.of());
        assertSame(ReadableBuffer.EMPTY, rb);
    }

    @Test
    public void testToWritableThrows()
    {
        ReadableBuffer rb = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(1)
            .flip());
        ReadableBuffer acc = ReadableBuffer.accumulate(List.of(rb));

        assertThrows(IllegalStateException.class, acc::toWritable);
    }

    @Test
    public void testGet()
    {
        ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)1)
            .put((byte)2)
            .flip());
        ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)3)
            .put((byte)4)
            .flip());
        ReadableBuffer acc = ReadableBuffer.accumulate(List.of(rb1, rb2));

        assertEquals(0, acc.position());
        assertEquals(4, acc.remaining());
        assertEquals(1, acc.get());

        assertEquals(1, acc.position());
        assertEquals(3, acc.remaining());
        assertEquals(2, acc.get());

        assertEquals(2, acc.position());
        assertEquals(2, acc.remaining());
        assertEquals(3, acc.get());

        assertEquals(3, acc.position());
        assertEquals(1, acc.remaining());
        assertEquals(4, acc.get());

        assertEquals(4, acc.position());
        assertEquals(0, acc.remaining());
        assertThrows(BufferUnderflowException.class, acc::get);
    }

    @Test
    public void testGetShort()
    {
        ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .putShort((short)1)
            .putShort((short)2)
            .flip());
        ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .putShort((short)3)
            .putShort((short)4)
            .flip());
        ReadableBuffer acc = ReadableBuffer.accumulate(List.of(rb1, rb2));

        assertEquals(0, acc.position());
        assertEquals(8, acc.remaining());
        assertEquals(1, acc.getShort());

        assertEquals(2, acc.position());
        assertEquals(6, acc.remaining());
        assertEquals(2, acc.getShort());

        assertEquals(4, acc.position());
        assertEquals(4, acc.remaining());
        assertEquals(3, acc.getShort());

        assertEquals(6, acc.position());
        assertEquals(2, acc.remaining());
        assertEquals(4, acc.getShort());

        assertEquals(8, acc.position());
        assertEquals(0, acc.remaining());
        assertThrows(BufferUnderflowException.class, acc::getShort);
    }

    @Test
    public void testFragmentedGetShort()
    {
        ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)0)
            .flip());
        ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)1)
            .flip());
        ReadableBuffer acc = ReadableBuffer.accumulate(List.of(rb1, rb2));

        assertEquals(0, acc.position());
        assertEquals(2, acc.remaining());
        assertEquals(1, acc.getShort());

        assertEquals(2, acc.position());
        assertEquals(0, acc.remaining());
        assertThrows(BufferUnderflowException.class, acc::getShort);
    }

    @Test
    public void testGetShortNotEnoughBytes()
    {
        ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .putShort((short)1)
            .flip());
        ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)2)
            .flip());
        ReadableBuffer acc = ReadableBuffer.accumulate(List.of(rb1, rb2));

        assertEquals(0, acc.position());
        assertEquals(3, acc.remaining());
        assertEquals(1, acc.getShort());

        assertEquals(2, acc.position());
        assertEquals(1, acc.remaining());
        assertThrows(BufferUnderflowException.class, acc::getShort);

        assertEquals(2, acc.position());
        assertEquals(1, acc.remaining());
        assertEquals(2, acc.get());
    }

    @Test
    public void testFragmentedGetShortNotEnoughBytes()
    {
        ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)1)
            .flip());
        ReadableBuffer acc = ReadableBuffer.accumulate(List.of(rb1));

        assertEquals(0, acc.position());
        assertEquals(1, acc.remaining());
        assertThrows(BufferUnderflowException.class, acc::getShort);

        assertEquals(0, acc.position());
        assertEquals(1, acc.remaining());
        assertEquals(1, acc.get());
    }

    @Test
    public void testGetInt()
    {
        ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(1)
            .putInt(2)
            .flip());
        ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(3)
            .putInt(4)
            .flip());
        ReadableBuffer acc = ReadableBuffer.accumulate(List.of(rb1, rb2));

        assertEquals(0, acc.position());
        assertEquals(16, acc.remaining());
        assertEquals(1, acc.getInt());

        assertEquals(4, acc.position());
        assertEquals(12, acc.remaining());
        assertEquals(2, acc.getInt());

        assertEquals(8, acc.position());
        assertEquals(8, acc.remaining());
        assertEquals(3, acc.getInt());

        assertEquals(12, acc.position());
        assertEquals(4, acc.remaining());
        assertEquals(4, acc.getInt());

        assertEquals(16, acc.position());
        assertEquals(0, acc.remaining());
        assertThrows(BufferUnderflowException.class, acc::getInt);
    }

    @Test
    public void testFragmentedGetInt()
    {
        List<ReadableBuffer> accumulatorCombinations = new ArrayList<>();

        {
            ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .flip());
            ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)1)
                .flip());
            accumulatorCombinations.add(ReadableBuffer.accumulate(List.of(rb1, rb2)));
        }
        {
            ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .put((byte)0)
                .put((byte)1)
                .flip());
            accumulatorCombinations.add(ReadableBuffer.accumulate(List.of(rb1, rb2)));
        }
        {
            ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            ReadableBuffer rb3 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            ReadableBuffer rb4 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)1)
                .flip());
            accumulatorCombinations.add(ReadableBuffer.accumulate(List.of(rb1, rb2, rb3, rb4)));
        }

        for (ReadableBuffer acc : accumulatorCombinations)
        {
            assertEquals(0, acc.position());
            assertEquals(4, acc.remaining());
            assertEquals(1, acc.getInt());

            assertEquals(4, acc.position());
            assertEquals(0, acc.remaining());
            assertThrows(BufferUnderflowException.class, acc::getInt);
        }
    }

    @Test
    public void testGetIntNotEnoughBytes()
    {
        ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(1)
            .flip());
        ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)2)
            .flip());
        ReadableBuffer acc = ReadableBuffer.accumulate(List.of(rb1, rb2));

        assertEquals(0, acc.position());
        assertEquals(5, acc.remaining());
        assertEquals(1, acc.getInt());

        assertEquals(4, acc.position());
        assertEquals(1, acc.remaining());
        assertThrows(BufferUnderflowException.class, acc::getInt);

        assertEquals(4, acc.position());
        assertEquals(1, acc.remaining());
        assertEquals(2, acc.get());
    }

    @Test
    public void testFragmentedGetIntNotEnoughBytes()
    {
        ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)0)
            .put((byte)1)
            .flip());
        ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)2)
            .flip());
        ReadableBuffer acc = ReadableBuffer.accumulate(List.of(rb1, rb2));

        assertEquals(0, acc.position());
        assertEquals(3, acc.remaining());
        assertThrows(BufferUnderflowException.class, acc::getInt);

        assertEquals(0, acc.position());
        assertEquals(3, acc.remaining());
        assertEquals(0, acc.get());
        assertEquals(1, acc.get());
        assertEquals(2, acc.get());
    }

    @Test
    public void testGetLong()
    {
        ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(20)
            .putLong(1L)
            .putLong(2L)
            .flip());
        ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(20)
            .putLong(3L)
            .putLong(4L)
            .flip());
        ReadableBuffer acc = ReadableBuffer.accumulate(List.of(rb1, rb2));

        assertEquals(0, acc.position());
        assertEquals(32, acc.remaining());
        assertEquals(1, acc.getLong());

        assertEquals(8, acc.position());
        assertEquals(24, acc.remaining());
        assertEquals(2, acc.getLong());

        assertEquals(16, acc.position());
        assertEquals(16, acc.remaining());
        assertEquals(3, acc.getLong());

        assertEquals(24, acc.position());
        assertEquals(8, acc.remaining());
        assertEquals(4, acc.getLong());

        assertEquals(32, acc.position());
        assertEquals(0, acc.remaining());
        assertThrows(BufferUnderflowException.class, acc::getLong);
    }

    @Test
    public void testFragmentedGetLong()
    {
        List<ReadableBuffer> accumulatorCombinations = new ArrayList<>();

        {
            ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .flip());
            ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)1)
                .flip());
            accumulatorCombinations.add(ReadableBuffer.accumulate(List.of(rb1, rb2)));
        }
        {
            ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)1)
                .flip());
            accumulatorCombinations.add(ReadableBuffer.accumulate(List.of(rb1, rb2)));
        }
        {
            ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            ReadableBuffer rb3 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            ReadableBuffer rb4 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            ReadableBuffer rb5 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            ReadableBuffer rb6 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            ReadableBuffer rb7 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            ReadableBuffer rb8 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)1)
                .flip());
            accumulatorCombinations.add(ReadableBuffer.accumulate(List.of(rb1, rb2, rb3, rb4, rb5, rb6, rb7, rb8)));
        }

        for (ReadableBuffer acc : accumulatorCombinations)
        {
            assertEquals(0, acc.position());
            assertEquals(8, acc.remaining());
            assertEquals(1, acc.getLong());

            assertEquals(8, acc.position());
            assertEquals(0, acc.remaining());
            assertThrows(BufferUnderflowException.class, acc::getLong);
        }
    }

    @Test
    public void testFragmentedGetLongNotEnoughBytes()
    {
        ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)0)
            .put((byte)0)
            .put((byte)0)
            .put((byte)1)
            .flip());
        ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)2)
            .put((byte)3)
            .put((byte)4)
            .flip());
        ReadableBuffer acc = ReadableBuffer.accumulate(List.of(rb1, rb2));

        assertEquals(0, acc.position());
        assertEquals(7, acc.remaining());
        assertThrows(BufferUnderflowException.class, acc::getLong);

        assertEquals(0, acc.position());
        assertEquals(7, acc.remaining());
        assertEquals(1, acc.getInt());
        assertEquals(2, acc.get());
        assertEquals(3, acc.get());
        assertEquals(4, acc.get());
    }

    @Test
    public void testPosition()
    {
        ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)0)
            .put((byte)1)
            .flip());
        ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)2)
            .flip());
        ReadableBuffer rb3 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)3)
            .put((byte)4)
            .put((byte)5)
            .flip());
        ReadableBuffer acc = ReadableBuffer.accumulate(List.of(rb1, rb2, rb3));

        assertEquals(0, acc.position());
        assertEquals(6, acc.remaining());

        acc.position(2);
        assertEquals(2, acc.position());
        assertEquals(4, acc.remaining());

        assertEquals(2, acc.get());
        assertEquals(3, acc.position());
        assertEquals(3, acc.remaining());

        acc.position(5);
        assertEquals(5, acc.get());
        assertEquals(6, acc.position());
        assertEquals(0, acc.remaining());

        acc.position(3);
        assertEquals(3, acc.position());
        assertEquals(3, acc.remaining());
        assertEquals(3, acc.get());

        assertThrows(IllegalArgumentException.class, () -> acc.position(7));
        assertThrows(IllegalArgumentException.class, () -> acc.position(-1));

        acc.position(0);
        assertEquals(0, acc.position());
        assertEquals(6, acc.remaining());
        assertEquals(0, acc.get());

        acc.position(6);
        assertThrows(BufferUnderflowException.class, acc::get);
    }

    @Test
    public void testCompact()
    {
        ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)0)
            .flip());
        ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)1)
            .flip());
        ReadableBuffer acc = ReadableBuffer.accumulate(List.of(rb1, rb2));

        assertThrows(IllegalStateException.class, acc::compact);
    }

    @Test
    public void testDrain()
    {
        ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)0)
            .put((byte)0)
            .flip());
        ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)0)
            .put((byte)1)
            .flip());
        ReadableBuffer acc = ReadableBuffer.accumulate(List.of(rb1, rb2));

        assertEquals(0, acc.position());
        assertEquals(4, acc.remaining());

        acc.drain();
        assertEquals(0, acc.position());
        assertEquals(0, acc.remaining());
    }

    @Test
    public void testByteBuffersNotAtZeroPositionGet()
    {
        ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)0)
            .put((byte)0)
            .flip()
            .position(1));
        ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)9)
            .put((byte)1)
            .flip()
            .position(1));
        ReadableBuffer acc = ReadableBuffer.accumulate(List.of(rb1, rb2));

        assertEquals(1, acc.position());
        assertEquals(2, acc.remaining());
        assertEquals(1, acc.getShort());

        assertEquals(4, acc.position());
        assertEquals(0, acc.remaining());
    }

    @Test
    public void testWriteTo() throws IOException
    {
        ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(11)
            .putInt(12)
            .flip());
        ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(13)
            .putInt(14)
            .flip());
        ReadableBuffer acc = ReadableBuffer.accumulate(List.of(rb1, rb2));
        assertEquals(0, acc.position());
        assertEquals(16, acc.remaining());

        List<Integer> writtenIntegers = new ArrayList<>();
        assertEquals(4L,
            acc.writeTo(input -> writtenIntegers.add(input.getInt()))
        );
        assertEquals(4, acc.position());
        assertEquals(12, acc.remaining());
        assertEquals(1, writtenIntegers.size());
        assertEquals(11, writtenIntegers.getFirst());

        writtenIntegers.clear();
        assertEquals(12L,
            acc.writeTo(input ->
            {
                while (input.hasRemaining())
                {
                    writtenIntegers.add(input.getInt());
                }
            })
        );
        assertEquals(16, acc.position());
        assertEquals(0, acc.remaining());
        assertEquals(3, writtenIntegers.size());
        assertEquals(12, writtenIntegers.get(0));
        assertEquals(13, writtenIntegers.get(1));
        assertEquals(14, writtenIntegers.get(2));
    }

    @Test
    public void testByteBuffersNotAtZeroPositionWriteTo() throws IOException
    {
        ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)0)
            .putInt(1)
            .flip()
            .position(1));
        ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)2)
            .putInt(3)
            .flip()
            .position(1));
        ReadableBuffer acc = ReadableBuffer.accumulate(List.of(rb1, rb2));

        assertEquals(1, acc.position());
        assertEquals(8, acc.remaining());

        AtomicInteger counter = new AtomicInteger();
        List<Number> written = new ArrayList<>();
        assertEquals(2L,
            acc.writeTo(input ->
            {
                counter.incrementAndGet();
                written.add(input.getShort());
            })
        );
        assertEquals(3, acc.position());
        assertEquals(6, acc.remaining());
        assertEquals(1, written.size());
        assertEquals((short)0, written.getFirst());
        assertEquals(1, counter.get());

        written.clear();
        counter.set(0);
        assertEquals(6L,
            acc.writeTo(input ->
            {
                if (counter.getAndIncrement() == 0)
                    written.add(input.getShort());
                else
                    written.add(input.getInt());
            })
        );
        assertEquals(10, acc.position());
        assertEquals(0, acc.remaining());
        assertEquals(2, written.size());
        assertEquals((short)1, written.get(0));
        assertEquals(3, written.get(1));
        assertEquals(2, counter.get());
    }

    @Test
    public void testWriteToResuming() throws IOException
    {
        ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .putShort((short)0)
            .putShort((short)1)
            .flip());
        ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .putShort((short)2)
            .putShort((short)3)
            .flip());
        ReadableBuffer acc = ReadableBuffer.accumulate(List.of(rb1, rb2));

        AtomicInteger counter = new AtomicInteger();
        List<Number> written = new ArrayList<>();
        assertEquals(2L,
            acc.writeTo(input ->
            {
                counter.getAndIncrement();
                written.add(input.getShort());
            })
        );
        assertEquals(2, acc.position());
        assertEquals(6, acc.remaining());
        assertEquals(1, counter.get());
        assertEquals(1, written.size());
        assertEquals((short)0, written.getFirst());

        counter.set(0);
        written.clear();
        assertEquals(4L,
            acc.writeTo(input ->
            {
                if (counter.getAndIncrement() == 0)
                {
                    written.add(input.getShort());
                    assertEquals(0, input.remaining());
                }
                else
                {
                    written.add(input.getShort());
                    assertEquals(2, input.remaining());
                }
            })
        );
        assertEquals(6, acc.position());
        assertEquals(2, acc.remaining());
        assertEquals(2, counter.get());
        assertEquals(2, written.size());
        assertEquals((short)1, written.get(0));
        assertEquals((short)2, written.get(1));
    }

    @Test
    public void testRetainRelease()
    {
        ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(0)
            .flip());
        ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(1)
            .flip());
        ReadableBuffer acc = ReadableBuffer.accumulate(List.of(rb1, rb2));
        assertFalse(rb1.release());
        assertFalse(rb2.release());

        acc.retain();
        assertFalse(acc.release());
        assertEquals(1, rb1.getRetained());
        assertEquals(1, rb2.getRetained());

        assertTrue(acc.release());
        assertEquals(0, rb1.getRetained());
        assertEquals(0, rb2.getRetained());
    }

    @Test
    public void testSlice()
    {
        ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)0)
            .putInt(1)
            .flip()
            .position(1));
        ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)2)
            .putInt(3)
            .flip()
            .position(1));
        ReadableBuffer acc = ReadableBuffer.accumulate(List.of(rb1, rb2));
        assertFalse(rb1.release());
        assertFalse(rb2.release());

        assertEquals(1, acc.position());
        assertEquals(8, acc.remaining());
        assertEquals(20, acc.capacity());

        ReadableBuffer slice = acc.slice();
        assertEquals(0, slice.position());
        assertEquals(8, slice.remaining());
        assertEquals(8, slice.capacity());

        assertTrue(slice.release());
        assertEquals(1, rb1.getRetained());
        assertEquals(1, rb2.getRetained());

        assertTrue(acc.release());
        assertEquals(0, rb1.getRetained());
        assertEquals(0, rb2.getRetained());
    }

    @Test
    public void testSlicePositionLength()
    {
        ReadableBuffer rb1 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)0)
            .flip());
        ReadableBuffer rb2 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)1)
            .flip());
        ReadableBuffer rb3 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)2)
            .flip());
        ReadableBuffer rb4 = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)3)
            .flip());
        ReadableBuffer acc = ReadableBuffer.accumulate(List.of(rb1, rb2, rb3, rb4));
        assertFalse(rb1.release());
        assertFalse(rb2.release());
        assertFalse(rb3.release());
        assertFalse(rb4.release());

        {
            ReadableBuffer slice = acc.slice(0, 2);
            assertEquals(0, slice.position());
            assertEquals(2, slice.remaining());
            assertEquals(2, slice.capacity());
            assertEquals(0, slice.get());
            assertEquals(1, slice.get());
            assertTrue(slice.release());
            assertEquals(1, rb1.getRetained());
            assertEquals(1, rb2.getRetained());
            assertEquals(1, rb3.getRetained());
            assertEquals(1, rb4.getRetained());
        }
        {
            ReadableBuffer slice = acc.slice(1, 3);
            assertEquals(0, slice.position());
            assertEquals(3, slice.remaining());
            assertEquals(3, slice.capacity());
            assertEquals(1, slice.get());
            assertEquals(2, slice.get());
            assertEquals(3, slice.get());
            assertTrue(slice.release());
            assertEquals(1, rb1.getRetained());
            assertEquals(1, rb2.getRetained());
            assertEquals(1, rb3.getRetained());
            assertEquals(1, rb4.getRetained());
        }
        {
            ReadableBuffer slice = acc.slice(3, 1);
            assertEquals(0, slice.position());
            assertEquals(1, slice.remaining());
            assertEquals(1, slice.capacity());
            assertEquals(3, slice.get());
            assertTrue(slice.release());
            assertEquals(1, rb1.getRetained());
            assertEquals(1, rb2.getRetained());
            assertEquals(1, rb3.getRetained());
            assertEquals(1, rb4.getRetained());
        }

        assertSame(ReadableBuffer.EMPTY, acc.slice(4, 0));

        assertThrows(IllegalArgumentException.class, () -> acc.slice(0, 5));
        assertThrows(IllegalArgumentException.class, () -> acc.slice(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> acc.slice(4, 1));
        assertThrows(IllegalArgumentException.class, () -> acc.slice(5, 0));

        assertTrue(acc.release());
        assertEquals(0, rb1.getRetained());
        assertEquals(0, rb2.getRetained());
        assertEquals(0, rb3.getRetained());
        assertEquals(0, rb4.getRetained());
    }
}
