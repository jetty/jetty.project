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
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.util.buffer.WritableBufferPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class MultiBufferTest
{
    @Test
    public void testEmptyListIsEmptyInstance()
    {
        RetainableByteBuffer rb = RetainableByteBuffer.wrap(List.of());
        assertSame(RetainableByteBuffer.empty(), rb);
    }

    @Test
    public void testGet()
    {
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)1)
            .put((byte)2)
            .flip());
        RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)3)
            .put((byte)4)
            .flip());
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1, rb2));

        assertEquals(0, acc.readPosition());
        assertEquals(4, acc.remaining());
        assertEquals(1, acc.get());

        assertEquals(1, acc.readPosition());
        assertEquals(3, acc.remaining());
        assertEquals(2, acc.get());

        assertEquals(2, acc.readPosition());
        assertEquals(2, acc.remaining());
        assertEquals(3, acc.get());

        assertEquals(3, acc.readPosition());
        assertEquals(1, acc.remaining());
        assertEquals(4, acc.get());

        assertEquals(4, acc.readPosition());
        assertEquals(0, acc.remaining());
        assertThrows(BufferUnderflowException.class, acc::get);
    }

    @Test
    public void testGetShort()
    {
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .putShort((short)1)
            .putShort((short)2)
            .flip());
        RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .putShort((short)3)
            .putShort((short)4)
            .flip());
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1, rb2));

        assertEquals(0, acc.readPosition());
        assertEquals(8, acc.remaining());
        assertEquals(1, acc.getShort());

        assertEquals(2, acc.readPosition());
        assertEquals(6, acc.remaining());
        assertEquals(2, acc.getShort());

        assertEquals(4, acc.readPosition());
        assertEquals(4, acc.remaining());
        assertEquals(3, acc.getShort());

        assertEquals(6, acc.readPosition());
        assertEquals(2, acc.remaining());
        assertEquals(4, acc.getShort());

        assertEquals(8, acc.readPosition());
        assertEquals(0, acc.remaining());
        assertThrows(BufferUnderflowException.class, acc::getShort);
    }

    @Test
    public void testFragmentedGetShort()
    {
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)0)
            .flip());
        RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)1)
            .flip());
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1, rb2));

        assertEquals(0, acc.readPosition());
        assertEquals(2, acc.remaining());
        assertEquals(1, acc.getShort());

        assertEquals(2, acc.readPosition());
        assertEquals(0, acc.remaining());
        assertThrows(BufferUnderflowException.class, acc::getShort);
    }

    @Test
    public void testGetShortNotEnoughBytes()
    {
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .putShort((short)1)
            .flip());
        RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)2)
            .flip());
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1, rb2));

        assertEquals(0, acc.readPosition());
        assertEquals(3, acc.remaining());
        assertEquals(1, acc.getShort());

        assertEquals(2, acc.readPosition());
        assertEquals(1, acc.remaining());
        assertThrows(BufferUnderflowException.class, acc::getShort);

        assertEquals(2, acc.readPosition());
        assertEquals(1, acc.remaining());
        assertEquals(2, acc.get());
    }

    @Test
    public void testFragmentedGetShortNotEnoughBytes()
    {
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)1)
            .flip());
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1));

        assertEquals(0, acc.readPosition());
        assertEquals(1, acc.remaining());
        assertThrows(BufferUnderflowException.class, acc::getShort);

        assertEquals(0, acc.readPosition());
        assertEquals(1, acc.remaining());
        assertEquals(1, acc.get());
    }

    @Test
    public void testGetInt()
    {
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(1)
            .putInt(2)
            .flip());
        RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(3)
            .putInt(4)
            .flip());
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1, rb2));

        assertEquals(0, acc.readPosition());
        assertEquals(16, acc.remaining());
        assertEquals(1, acc.getInt());

        assertEquals(4, acc.readPosition());
        assertEquals(12, acc.remaining());
        assertEquals(2, acc.getInt());

        assertEquals(8, acc.readPosition());
        assertEquals(8, acc.remaining());
        assertEquals(3, acc.getInt());

        assertEquals(12, acc.readPosition());
        assertEquals(4, acc.remaining());
        assertEquals(4, acc.getInt());

        assertEquals(16, acc.readPosition());
        assertEquals(0, acc.remaining());
        assertThrows(BufferUnderflowException.class, acc::getInt);
    }

    @Test
    public void testFragmentedGetInt()
    {
        List<RetainableByteBuffer> accumulatorCombinations = new ArrayList<>();

        {
            RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)1)
                .flip());
            accumulatorCombinations.add(RetainableByteBuffer.wrap(List.of(rb1, rb2)));
        }
        {
            RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .put((byte)0)
                .put((byte)1)
                .flip());
            accumulatorCombinations.add(RetainableByteBuffer.wrap(List.of(rb1, rb2)));
        }
        {
            RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb3 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb4 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)1)
                .flip());
            accumulatorCombinations.add(RetainableByteBuffer.wrap(List.of(rb1, rb2, rb3, rb4)));
        }

        for (RetainableByteBuffer acc : accumulatorCombinations)
        {
            assertEquals(0, acc.readPosition());
            assertEquals(4, acc.remaining());
            assertEquals(1, acc.getInt());

            assertEquals(4, acc.readPosition());
            assertEquals(0, acc.remaining());
            assertThrows(BufferUnderflowException.class, acc::getInt);
        }
    }

    @Test
    public void testGetIntNotEnoughBytes()
    {
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(1)
            .flip());
        RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)2)
            .flip());
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1, rb2));

        assertEquals(0, acc.readPosition());
        assertEquals(5, acc.remaining());
        assertEquals(1, acc.getInt());

        assertEquals(4, acc.readPosition());
        assertEquals(1, acc.remaining());
        assertThrows(BufferUnderflowException.class, acc::getInt);

        assertEquals(4, acc.readPosition());
        assertEquals(1, acc.remaining());
        assertEquals(2, acc.get());
    }

    @Test
    public void testFragmentedGetIntNotEnoughBytes()
    {
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)0)
            .put((byte)1)
            .flip());
        RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)2)
            .flip());
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1, rb2));

        assertEquals(0, acc.readPosition());
        assertEquals(3, acc.remaining());
        assertThrows(BufferUnderflowException.class, acc::getInt);

        assertEquals(0, acc.readPosition());
        assertEquals(3, acc.remaining());
        assertEquals(0, acc.get());
        assertEquals(1, acc.get());
        assertEquals(2, acc.get());
    }

    @Test
    public void testGetLong()
    {
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(20)
            .putLong(1L)
            .putLong(2L)
            .flip());
        RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(20)
            .putLong(3L)
            .putLong(4L)
            .flip());
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1, rb2));

        assertEquals(0, acc.readPosition());
        assertEquals(32, acc.remaining());
        assertEquals(1, acc.getLong());

        assertEquals(8, acc.readPosition());
        assertEquals(24, acc.remaining());
        assertEquals(2, acc.getLong());

        assertEquals(16, acc.readPosition());
        assertEquals(16, acc.remaining());
        assertEquals(3, acc.getLong());

        assertEquals(24, acc.readPosition());
        assertEquals(8, acc.remaining());
        assertEquals(4, acc.getLong());

        assertEquals(32, acc.readPosition());
        assertEquals(0, acc.remaining());
        assertThrows(BufferUnderflowException.class, acc::getLong);
    }

    @Test
    public void testFragmentedGetLong()
    {
        List<RetainableByteBuffer> accumulatorCombinations = new ArrayList<>();

        {
            RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)1)
                .flip());
            accumulatorCombinations.add(RetainableByteBuffer.wrap(List.of(rb1, rb2)));
        }
        {
            RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)1)
                .flip());
            accumulatorCombinations.add(RetainableByteBuffer.wrap(List.of(rb1, rb2)));
        }
        {
            RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb3 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb4 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb5 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb6 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb7 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb8 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)1)
                .flip());
            accumulatorCombinations.add(RetainableByteBuffer.wrap(List.of(rb1, rb2, rb3, rb4, rb5, rb6, rb7, rb8)));
        }

        for (RetainableByteBuffer acc : accumulatorCombinations)
        {
            assertEquals(0, acc.readPosition());
            assertEquals(8, acc.remaining());
            assertEquals(1, acc.getLong());

            assertEquals(8, acc.readPosition());
            assertEquals(0, acc.remaining());
            assertThrows(BufferUnderflowException.class, acc::getLong);
        }
    }

    @Test
    public void testFragmentedGetLongNotEnoughBytes()
    {
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)0)
            .put((byte)0)
            .put((byte)0)
            .put((byte)1)
            .flip());
        RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)2)
            .put((byte)3)
            .put((byte)4)
            .flip());
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1, rb2));

        assertEquals(0, acc.readPosition());
        assertEquals(7, acc.remaining());
        assertThrows(BufferUnderflowException.class, acc::getLong);

        assertEquals(0, acc.readPosition());
        assertEquals(7, acc.remaining());
        assertEquals(1, acc.getInt());
        assertEquals(2, acc.get());
        assertEquals(3, acc.get());
        assertEquals(4, acc.get());
    }

    @Test
    public void testGetByteArray()
    {
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(20)
            .putLong(1L)
            .putLong(2L)
            .flip());
        RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(20)
            .putLong(3L)
            .putLong(4L)
            .flip());
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1, rb2));
        byte[] bytes = new byte[8];

        assertEquals(0, acc.readPosition());
        assertEquals(32, acc.remaining());
        acc.get(bytes);
        assertArrayEquals(new byte[]{0, 0, 0, 0, 0, 0, 0, 1}, bytes);

        assertEquals(8, acc.readPosition());
        assertEquals(24, acc.remaining());
        acc.get(bytes);
        assertArrayEquals(new byte[]{0, 0, 0, 0, 0, 0, 0, 2}, bytes);

        assertEquals(16, acc.readPosition());
        assertEquals(16, acc.remaining());
        acc.get(bytes);
        assertArrayEquals(new byte[]{0, 0, 0, 0, 0, 0, 0, 3}, bytes);

        assertEquals(24, acc.readPosition());
        assertEquals(8, acc.remaining());
        acc.get(bytes);
        assertArrayEquals(new byte[]{0, 0, 0, 0, 0, 0, 0, 4}, bytes);

        assertEquals(32, acc.readPosition());
        assertEquals(0, acc.remaining());
        assertThrows(BufferUnderflowException.class, () -> acc.get(bytes));
    }

    @Test
    public void testFragmentedGetByteArray()
    {
        List<RetainableByteBuffer> accumulatorCombinations = new ArrayList<>();

        {
            RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)1)
                .flip());
            accumulatorCombinations.add(RetainableByteBuffer.wrap(List.of(rb1, rb2)));
        }
        {
            RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)0)
                .put((byte)1)
                .flip());
            accumulatorCombinations.add(RetainableByteBuffer.wrap(List.of(rb1, rb2)));
        }
        {
            RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb3 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb4 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb5 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb6 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb7 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)0)
                .flip());
            RetainableByteBuffer rb8 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
                .put((byte)1)
                .flip());
            accumulatorCombinations.add(RetainableByteBuffer.wrap(List.of(rb1, rb2, rb3, rb4, rb5, rb6, rb7, rb8)));
        }

        byte[] bytes = new byte[8];
        byte[] expected = new byte[]{0, 0, 0, 0, 0, 0, 0, 1};
        for (RetainableByteBuffer acc : accumulatorCombinations)
        {
            assertEquals(0, acc.readPosition());
            assertEquals(8, acc.remaining());
            acc.get(bytes);
            assertArrayEquals(expected, bytes);

            assertEquals(8, acc.readPosition());
            assertEquals(0, acc.remaining());
            assertThrows(BufferUnderflowException.class, () -> acc.get(bytes));
        }
    }

    @Test
    public void testFragmentedGetByteArrayNotEnoughBytes()
    {
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)0)
            .put((byte)0)
            .put((byte)0)
            .put((byte)1)
            .flip());
        RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)2)
            .put((byte)3)
            .put((byte)4)
            .flip());
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1, rb2));

        assertEquals(0, acc.readPosition());
        assertEquals(7, acc.remaining());
        byte[] bytes = new byte[8];
        assertThrows(BufferUnderflowException.class, () -> acc.get(bytes));

        assertEquals(1, acc.getInt());
        assertEquals(2, acc.get());
        assertEquals(3, acc.get());
        assertEquals(4, acc.get());
    }

    @Test
    public void testPosition()
    {
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)0)
            .put((byte)1)
            .flip());
        RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)2)
            .flip());
        RetainableByteBuffer rb3 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)3)
            .put((byte)4)
            .put((byte)5)
            .flip());
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1, rb2, rb3));

        assertEquals(0, acc.readPosition());
        assertEquals(6, acc.remaining());

        acc.readPosition(2);
        assertEquals(2, acc.readPosition());
        assertEquals(4, acc.remaining());

        assertEquals(2, acc.get());
        assertEquals(3, acc.readPosition());
        assertEquals(3, acc.remaining());

        acc.readPosition(5);
        assertEquals(5, acc.get());
        assertEquals(6, acc.readPosition());
        assertEquals(0, acc.remaining());

        acc.readPosition(3);
        assertEquals(3, acc.readPosition());
        assertEquals(3, acc.remaining());
        assertEquals(3, acc.get());

        assertThrows(IllegalArgumentException.class, () -> acc.readPosition(7));
        assertThrows(IllegalArgumentException.class, () -> acc.readPosition(-1));

        acc.readPosition(0);
        assertEquals(0, acc.readPosition());
        assertEquals(6, acc.remaining());
        assertEquals(0, acc.get());

        acc.readPosition(6);
        assertThrows(BufferUnderflowException.class, acc::get);
    }

    @Test
    public void testByteBuffersNotAtZeroPositionGet()
    {
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)0)
            .put((byte)0)
            .flip()
            .position(1));
        RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)9)
            .put((byte)1)
            .flip()
            .position(1));
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1, rb2));

        assertEquals(0, acc.readPosition());
        assertEquals(2, acc.remaining());
        assertEquals(1, acc.getShort());

        assertEquals(2, acc.readPosition());
        assertEquals(0, acc.remaining());
    }

    @Test
    public void testEmptyBufferWriteToCallsTarget() throws Exception
    {
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of());

        AtomicInteger writeCount = new AtomicInteger();
        long written = acc.writeTo(new TestGatheringTarget()
        {
            @Override
            public long write(FileChannel input, long position, long count)
            {
                assertEquals(0L, count);
                writeCount.incrementAndGet();
                return 0;
            }

            @Override
            public long write(ByteBuffer[] inputs)
            {
                long totalRemaining = Arrays.stream(inputs).mapToLong(ByteBuffer::remaining).sum();
                assertEquals(0L, totalRemaining);
                writeCount.incrementAndGet();
                return totalRemaining;
            }

            @Override
            public long write(ByteBuffer input)
            {
                assertEquals(0L, input.remaining());
                writeCount.incrementAndGet();
                return input.remaining();
            }
        });
        assertEquals(0L, written);
        assertEquals(1, writeCount.get());
    }

    @Test
    public void testWriteToGatheringOnly() throws IOException
    {
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(11)
            .putInt(12)
            .flip());
        RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(13)
            .putInt(14)
            .flip());
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1, rb2));
        assertEquals(0, acc.readPosition());
        assertEquals(16, acc.remaining());

        List<Integer> writtenIntegers = new ArrayList<>();
        long written = acc.writeTo(new RetainableByteBuffer.GatheringTarget()
        {
            @Override
            public long write(ByteBuffer[] inputs)
            {
                long result = 0;
                for (ByteBuffer input : inputs)
                {
                    while (input.hasRemaining())
                    {
                        writtenIntegers.add(input.getInt());
                        result += Integer.BYTES;
                    }
                }
                return result;
            }

            @Override
            public long write(ByteBuffer input)
            {
                fail("gathering write should have been called instead");
                return -1;
            }
        });
        assertEquals(16, written);

        assertEquals(16, acc.readPosition());
        assertEquals(0, acc.remaining());
        assertEquals(4, writtenIntegers.size());
        assertEquals(11, writtenIntegers.get(0));
        assertEquals(12, writtenIntegers.get(1));
        assertEquals(13, writtenIntegers.get(2));
        assertEquals(14, writtenIntegers.get(3));
    }

    @Test
    public void testWriteToGatheringAndTransferring() throws IOException
    {
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(11)
            .putInt(12)
            .flip());
        RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(13)
            .putInt(14)
            .flip());
        Path testResourcePathFile = MavenTestingUtils.getTestResourcePathFile("resource.txt");
        RetainableByteBuffer rb3 = RetainableByteBuffer.wrap(testResourcePathFile, WritableBufferPool.SIZED_NON_POOLING);
        RetainableByteBuffer rb4 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(15)
            .putInt(16)
            .flip());
        RetainableByteBuffer rb5 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(17)
            .putInt(18)
            .flip());

        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1, rb2, rb3, rb4, rb5));
        assertEquals(0, acc.readPosition());
        assertEquals(52, acc.remaining());

        List<Object> writtenObjects = new ArrayList<>();
        long written = acc.writeTo(new TestGatheringTarget()
        {
            @Override
            public long write(FileChannel input, long position, long count) throws IOException
            {
                ByteBuffer bb = ByteBuffer.allocate((int)count);
                input.read(bb, position);
                bb.flip();
                String string = BufferUtil.toString(bb);
                writtenObjects.add(string);
                return count;
            }

            @Override
            public long write(ByteBuffer[] inputs)
            {
                long result = 0;
                for (ByteBuffer input : inputs)
                {
                    while (input.hasRemaining())
                    {
                        writtenObjects.add(input.getInt());
                        result += Integer.BYTES;
                    }
                }
                return result;
            }

            @Override
            public long write(ByteBuffer input)
            {
                fail("gathering write should have been called instead");
                return -1;
            }
        });
        assertEquals(52, written);

        assertEquals(52, acc.readPosition());
        assertEquals(0, acc.remaining());
        assertEquals(9, writtenObjects.size());
        assertEquals(11, writtenObjects.get(0));
        assertEquals(12, writtenObjects.get(1));
        assertEquals(13, writtenObjects.get(2));
        assertEquals(14, writtenObjects.get(3));
        assertEquals("This is a text file\n", writtenObjects.get(4));
        assertEquals(15, writtenObjects.get(5));
        assertEquals(16, writtenObjects.get(6));
        assertEquals(17, writtenObjects.get(7));
        assertEquals(18, writtenObjects.get(8));
    }

    private abstract static class TestGatheringTarget implements RetainableByteBuffer.GatheringTarget, RetainableByteBuffer.TransferringTarget
    {
    }

    @Test
    public void testWriteTo() throws IOException
    {
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(11)
            .putInt(12)
            .flip());
        RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(13)
            .putInt(14)
            .flip());
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1, rb2));
        assertEquals(0, acc.readPosition());
        assertEquals(16, acc.remaining());

        List<Integer> writtenIntegers = new ArrayList<>();
        assertEquals(4L,
            acc.writeTo(input ->
            {
                writtenIntegers.add(input.getInt());
                return Integer.BYTES;
            })
        );
        assertEquals(4, acc.readPosition());
        assertEquals(12, acc.remaining());
        assertEquals(1, writtenIntegers.size());
        assertEquals(11, writtenIntegers.getFirst());

        writtenIntegers.clear();
        assertEquals(12L,
            acc.writeTo(input ->
            {
                long result = 0;
                while (input.hasRemaining())
                {
                    writtenIntegers.add(input.getInt());
                    result += Integer.BYTES;
                }
                return result;
            })
        );
        assertEquals(16, acc.readPosition());
        assertEquals(0, acc.remaining());
        assertEquals(3, writtenIntegers.size());
        assertEquals(12, writtenIntegers.get(0));
        assertEquals(13, writtenIntegers.get(1));
        assertEquals(14, writtenIntegers.get(2));
    }

    @Test
    public void testByteBuffersNotAtZeroPositionWriteTo() throws IOException
    {
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)0)
            .putInt(1)
            .flip()
            .position(1));
        RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)2)
            .putInt(3)
            .flip()
            .position(1));
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1, rb2));

        assertEquals(0, acc.readPosition());
        assertEquals(8, acc.remaining());

        AtomicInteger counter = new AtomicInteger();
        List<Number> written = new ArrayList<>();
        assertEquals(2L,
            acc.writeTo(input ->
            {
                counter.incrementAndGet();
                written.add(input.getShort());
                return Short.BYTES;
            })
        );
        assertEquals(2, acc.readPosition());
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
                {
                    written.add(input.getShort());
                    return Short.BYTES;
                }
                else
                {
                    written.add(input.getInt());
                    return Integer.BYTES;
                }
            })
        );
        assertEquals(8, acc.readPosition());
        assertEquals(0, acc.remaining());
        assertEquals(2, written.size());
        assertEquals((short)1, written.get(0));
        assertEquals(3, written.get(1));
        assertEquals(2, counter.get());
    }

    @Test
    public void testWriteToResuming() throws IOException
    {
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .putShort((short)0)
            .putShort((short)1)
            .flip());
        RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .putShort((short)2)
            .putShort((short)3)
            .flip());
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1, rb2));

        AtomicInteger counter = new AtomicInteger();
        List<Number> written = new ArrayList<>();
        assertEquals(2L,
            acc.writeTo(input ->
            {
                counter.getAndIncrement();
                written.add(input.getShort());
                return Short.BYTES;
            })
        );
        assertEquals(2, acc.readPosition());
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
                    return Short.BYTES;
                }
                else
                {
                    written.add(input.getShort());
                    assertEquals(2, input.remaining());
                    return Short.BYTES;
                }
            })
        );
        assertEquals(6, acc.readPosition());
        assertEquals(2, acc.remaining());
        assertEquals(2, counter.get());
        assertEquals(2, written.size());
        assertEquals((short)1, written.get(0));
        assertEquals((short)2, written.get(1));
    }

    @Test
    public void testRetainRelease()
    {
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(0)
            .flip());
        RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(1)
            .flip());
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1, rb2));
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
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)0)
            .putInt(1)
            .flip()
            .position(1));
        RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)2)
            .putInt(3)
            .flip()
            .position(1));
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1, rb2));
        assertFalse(rb1.release());
        assertFalse(rb2.release());

        assertEquals(0, acc.readPosition());
        assertEquals(8, acc.remaining());
        assertEquals(8, acc.capacity());

        RetainableByteBuffer slice = acc.slice();
        assertEquals(0, slice.readPosition());
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
        RetainableByteBuffer rb1 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)0)
            .flip());
        RetainableByteBuffer rb2 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)1)
            .flip());
        RetainableByteBuffer rb3 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)2)
            .flip());
        RetainableByteBuffer rb4 = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .put((byte)3)
            .flip());
        RetainableByteBuffer acc = RetainableByteBuffer.wrap(List.of(rb1, rb2, rb3, rb4));
        assertFalse(rb1.release());
        assertFalse(rb2.release());
        assertFalse(rb3.release());
        assertFalse(rb4.release());

        {
            RetainableByteBuffer slice = acc.slice(0, 2);
            assertEquals(0, slice.readPosition());
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
            RetainableByteBuffer slice = acc.slice(1, 3);
            assertEquals(0, slice.readPosition());
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
            RetainableByteBuffer slice = acc.slice(3, 1);
            assertEquals(0, slice.readPosition());
            assertEquals(1, slice.remaining());
            assertEquals(1, slice.capacity());
            assertEquals(3, slice.get());
            assertTrue(slice.release());
            assertEquals(1, rb1.getRetained());
            assertEquals(1, rb2.getRetained());
            assertEquals(1, rb3.getRetained());
            assertEquals(1, rb4.getRetained());
        }

        assertSame(RetainableByteBuffer.empty(), acc.slice(4, 0));

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

    @Test
    public void testSlicePositionLengthInterleavedPositions()
    {
        List<RetainableByteBuffer> buffers = List.of(
            allocate(9, (byte)1),
            allocate(76, (byte)2),
            allocate(9, (byte)3),
            allocate(16384, (byte)4),
            allocate(9, (byte)5),
            allocate(16384, (byte)6),
            allocate(9, (byte)7),
            allocate(16384, (byte)8),
            allocate(9, (byte)9),
            allocate(16383, (byte)10)
        );

        RetainableByteBuffer acc = RetainableByteBuffer.wrap(buffers);
        acc.readPosition(16384);

        {
            RetainableByteBuffer slice = acc.slice(16384, 16384);
            assertEquals(16384, slice.remaining());
            slice.release();
        }
        {
            RetainableByteBuffer slice = acc.slice(32768, 16384);
            assertEquals(16384, slice.remaining());
            slice.release();
        }

        buffers.forEach(RetainableByteBuffer::release);
    }

    private RetainableByteBuffer allocate(int capacity, byte fill)
    {
        byte[] bytes = new byte[capacity];
        Arrays.fill(bytes, fill);
        return RetainableByteBuffer.wrap(bytes);
    }
}
