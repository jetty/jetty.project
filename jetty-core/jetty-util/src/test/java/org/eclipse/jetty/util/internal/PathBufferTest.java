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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.util.buffer.WritableBufferPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class PathBufferTest
{
    public WorkDir workDir;

    @Test
    public void testRelativeGets() throws Exception
    {
        Path targetFile = workDir.getEmptyPathDir().resolve("testGetInt.txt");
        Files.writeString(targetFile, "    aaaa", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        try (RetainableByteBuffer buffer = RetainableByteBuffer.wrap(targetFile, WritableBufferPool.SIZED_NON_POOLING))
        {
            assertEquals(8L, buffer.remaining());
            assertEquals(0L, buffer.readPosition());

            assertEquals(0x20202020, buffer.getInt());
            assertEquals(4L, buffer.remaining());
            assertEquals(4L, buffer.readPosition());

            assertEquals(0x61616161, buffer.getInt());
            assertEquals(0L, buffer.remaining());
            assertEquals(8L, buffer.readPosition());

            buffer.readPosition(5L);
            assertEquals(3L, buffer.remaining());
            assertEquals(5L, buffer.readPosition());

            assertEquals(0x6161, buffer.getShort());
            assertEquals(1L, buffer.remaining());
            assertEquals(7L, buffer.readPosition());

            assertEquals(0x61, buffer.get());
            assertEquals(0L, buffer.remaining());
            assertEquals(8L, buffer.readPosition());

            assertThrows(BufferUnderflowException.class, buffer::get);

            buffer.readPosition(7L);
            assertThrows(BufferUnderflowException.class, buffer::getShort);
            assertEquals(7L, buffer.readPosition());
        }
    }

    @Test
    public void testAbsoluteGets() throws Exception
    {
        Path targetFile = workDir.getEmptyPathDir().resolve("testGetInt.txt");
        Files.writeString(targetFile, "    aaaa", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        try (RetainableByteBuffer buffer = RetainableByteBuffer.wrap(targetFile, WritableBufferPool.SIZED_NON_POOLING))
        {
            assertEquals(8L, buffer.remaining());

            assertEquals(0x20202020, buffer.getInt(0L));
            assertEquals(8L, buffer.remaining());

            assertEquals(0x61616161, buffer.getInt(4L));
            assertEquals(8L, buffer.remaining());

            buffer.readPosition(5L);
            assertEquals(3L, buffer.remaining());

            assertEquals(0x6161, buffer.getShort(5L));
            assertEquals(3L, buffer.remaining());

            assertEquals(0x61, buffer.get(7L));
            assertEquals(3L, buffer.remaining());

            assertThrows(BufferUnderflowException.class, () -> buffer.getShort(7L));
            assertEquals(5L, buffer.readPosition());
        }
    }

    @Test
    public void testGetBytes() throws Exception
    {
        Path testResourcePathFile = MavenTestingUtils.getTestResourcePathFile("resource.txt");
        try (RetainableByteBuffer buffer = RetainableByteBuffer.wrap(testResourcePathFile, WritableBufferPool.SIZED_NON_POOLING))
        {
            byte[] bytes = new byte[Math.toIntExact(buffer.remaining())];
            buffer.get(bytes);
            assertEquals(0L, buffer.remaining());
            assertEquals(20L, buffer.readPosition());
            assertEquals("This is a text file\n", new String(bytes));
        }
    }

    @Test
    public void testSlice() throws Exception
    {
        Path testResourcePathFile = MavenTestingUtils.getTestResourcePathFile("resource.txt");
        try (RetainableByteBuffer buffer = RetainableByteBuffer.wrap(testResourcePathFile, WritableBufferPool.SIZED_NON_POOLING))
        {
            byte[] bytes = new byte[Math.toIntExact(buffer.remaining())];
            buffer.get(bytes);
            assertEquals(0L, buffer.remaining());
            assertEquals("This is a text file\n", new String(bytes));

            try (RetainableByteBuffer slice = buffer.slice())
            {
                assertEquals(0L, slice.remaining());
            }
        }
    }

    @Test
    public void testSlice2Args() throws Exception
    {
        Path testResourcePathFile = MavenTestingUtils.getTestResourcePathFile("resource.txt");
        try (RetainableByteBuffer buffer = RetainableByteBuffer.wrap(testResourcePathFile, WritableBufferPool.SIZED_NON_POOLING))
        {
            assertEquals(20L, buffer.remaining());

            try (RetainableByteBuffer slice = buffer.slice(5L, 10L))
            {
                assertEquals(0L, slice.readPosition());
                assertEquals(10L, slice.remaining());
                byte[] bytes = new byte[10];
                slice.get(bytes);
                assertEquals("is a text ", new String(bytes));
            }
        }
    }

    @Test
    public void testWriteTo() throws Exception
    {
        Path emptyPathDir = workDir.getEmptyPathDir();
        Path targetFile = emptyPathDir.resolve("testWriteTo.txt");
        try (FileChannel targetFc = FileChannel.open(targetFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
        {
            Path testResourcePathFile = MavenTestingUtils.getTestResourcePathFile("resource.txt");
            try (RetainableByteBuffer buffer = RetainableByteBuffer.wrap(testResourcePathFile, WritableBufferPool.SIZED_NON_POOLING))
            {
                long totalWritten = 0L;
                for (int i = 0; i < 20; i++)
                {
                    totalWritten += buffer.read(input ->
                    {
                        int written = targetFc.write(input.slice().limit(1));
                        input.position(input.position() + written);
                        return written;
                    });
                }
                assertEquals(20L, totalWritten);
                assertEquals(0L, buffer.remaining());
            }
            assertEquals("This is a text file\n", Files.readString(targetFile));
        }
    }

    @Test
    public void testWriteToTransferring() throws Exception
    {
        Path emptyPathDir = workDir.getEmptyPathDir();
        Path targetFile = emptyPathDir.resolve("testWriteToTransferring.txt");
        try (FileChannel targetFc = FileChannel.open(targetFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))
        {
            Path testResourcePathFile = MavenTestingUtils.getTestResourcePathFile("resource.txt");
            try (RetainableByteBuffer buffer = RetainableByteBuffer.wrap(testResourcePathFile, WritableBufferPool.SIZED_NON_POOLING))
            {
                long written = buffer.read(new RetainableByteBuffer.TransferringReader()
                {
                    @Override
                    public long readFrom(FileChannel input, long position, long count) throws IOException
                    {
                        return input.transferTo(position, count, targetFc);
                    }

                    @Override
                    public long readFrom(ByteBuffer input) throws IOException
                    {
                        throw new IOException("Should not be called");
                    }
                });
                assertEquals(20L, written);
                assertEquals(0L, buffer.remaining());
            }
            assertEquals("This is a text file\n", Files.readString(targetFile));
        }
    }

    @Test
    public void testWriteToAccumulated() throws Exception
    {
        try (RetainableByteBuffer.Mutable b1 = RetainableByteBuffer.Mutable.allocate(20, false))
        {
            b1.put("-- prefix --\n".getBytes(StandardCharsets.UTF_8));

            Path testResourcePathFile = MavenTestingUtils.getTestResourcePathFile("resource.txt");
            try (RetainableByteBuffer b2 = RetainableByteBuffer.wrap(testResourcePathFile, WritableBufferPool.SIZED_NON_POOLING))
            {
                try (RetainableByteBuffer.Mutable b3 = RetainableByteBuffer.Mutable.allocate(20, false))
                {
                    b3.put("-- suffix --\n".getBytes(StandardCharsets.UTF_8));

                    RetainableByteBuffer acc = RetainableByteBuffer.merge(b1, b2, b3);

                    StringBuilder sb = new StringBuilder();
                    long written = acc.read(input ->
                    {
                        String string = BufferUtil.toString(input, StandardCharsets.UTF_8);
                        input.position(input.position() + string.length());
                        sb.append(string);
                        return string.length();
                    });
                    assertEquals("-- prefix --\nThis is a text file\n-- suffix --\n".length(), written);
                    assertEquals("-- prefix --\nThis is a text file\n-- suffix --\n", sb.toString());
                }
            }
        }
    }

    @Test
    public void testReadWithAsyncOperation() throws Exception
    {
        WritableBufferPool.Sized bufferPool = new WritableBufferPool.Sized(1024, false, (size, direct) ->
        {
            ByteBuffer bytes = direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size);
            try (Retainable.ReferenceCounter retainable = new Retainable.ReferenceCounter())
            {
                return new SingleMutableBuffer(bytes, retainable, true)
                {
                    @Override
                    public boolean release()
                    {
                        boolean released = super.release();
                        if (released)
                        {
                            // Clear the bytes to verify that the buffer is not used after release.
                            for (int i = 0; i < bytes.capacity(); ++i)
                            {
                                bytes.put(i, (byte)0);
                            }
                        }
                        return released;
                    }
                };
            }
        });
        Path targetFile = workDir.getEmptyPathDir().resolve("readWithAsync.txt");
        // Create a file large enough to be read in multiple passes.
        int length = 2 * bufferPool.getSize();
        String digits = "0123456789ABCDEF";
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; ++i)
        {
            builder.append(digits.charAt(ThreadLocalRandom.current().nextInt(digits.length())));
        }
        String content = builder.toString();
        Files.writeString(targetFile, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        try (RetainableByteBuffer buffer = RetainableByteBuffer.wrap(targetFile, bufferPool))
        {
            long total = buffer.remaining();

            AtomicInteger count = new AtomicInteger();
            AtomicReference<ByteBuffer> current = new AtomicReference<>();
            buffer.read(b ->
            {
                count.incrementAndGet();
                current.set(b);
                // ByteBuffer b must not be modified, as we return 0.
                ByteBuffer slice = b.slice();
                // Initiate an async operation.
                slice.get();
                return 0;
            });
            assertEquals(1, count.get());
            assertEquals(total, buffer.remaining());

            // Simulate that the async operation is complete.
            ByteBuffer byteBuffer = current.get();
            int firstLength = byteBuffer.remaining();
            buffer.consume(firstLength);

            // Copy the content to verify later.
            StringBuilder result = new StringBuilder(firstLength);
            while (byteBuffer.hasRemaining())
            {
                result.append((char)byteBuffer.get());
            }

            // Read again.
            buffer.read(b ->
            {
                count.incrementAndGet();
                current.set(b);
                return 0;
            });
            assertEquals(2, count.get());
            assertEquals(total - firstLength, buffer.remaining());

            byteBuffer = current.get();
            int secondLength = byteBuffer.remaining();
            buffer.consume(secondLength);

            while (byteBuffer.hasRemaining())
            {
                result.append((char)byteBuffer.get());
            }

            // Verify that the reader is not invoked again.
            assertFalse(buffer.hasRemaining());
            buffer.read(_ ->
            {
                count.incrementAndGet();
                return 0;
            });
            assertEquals(2, count.get());

            assertEquals(content, result.toString());
        }
    }

    @Test
    public void testSliceReadWithAsyncOperationDoesNotLeak() throws Exception
    {
        AtomicInteger outstanding = new AtomicInteger();
        WritableBufferPool.Sized bufferPool = new WritableBufferPool.Sized(1024, false, (size, direct) ->
        {
            ByteBuffer bytes = direct ? ByteBuffer.allocateDirect(size) : ByteBuffer.allocate(size);
            try (Retainable.ReferenceCounter retainable = new Retainable.ReferenceCounter())
            {
                outstanding.incrementAndGet();
                return new SingleMutableBuffer(bytes, retainable, true)
                {
                    @Override
                    public boolean release()
                    {
                        boolean released = super.release();
                        if (released)
                            outstanding.decrementAndGet();
                        return released;
                    }
                };
            }
        });
        Path targetFile = workDir.getEmptyPathDir().resolve("sliceReadWithAsync.txt");
        Files.write(targetFile, new byte[2 * bufferPool.getSize()], StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        // A run that releases the slice before the parent.
        RetainableByteBuffer buffer = RetainableByteBuffer.wrap(targetFile, bufferPool);
        RetainableByteBuffer slice = buffer.slice(bufferPool.getSize() / 2, bufferPool.getSize());
        assertTrue(buffer.isRetained());

        // Returning 0 keeps the file buffer of the slice, as the Reader may use it asynchronously.
        slice.read(_ -> 0);
        assertEquals(1, outstanding.get());

        // Release the slice before the parent.
        assertTrue(slice.release());
        assertFalse(buffer.isRetained());
        assertEquals(0, outstanding.get());
        assertTrue(buffer.release());
        assertEquals(0, outstanding.get());

        // Another run that releases the parent before the slice.
        buffer = RetainableByteBuffer.wrap(targetFile, bufferPool);
        slice = buffer.slice(bufferPool.getSize() / 2, bufferPool.getSize());
        buffer.read(_ -> 0);
        slice.read(_ -> 0);
        assertEquals(2, outstanding.get());

        // The parent is still retained by the slice.
        assertFalse(buffer.release());
        assertEquals(2, outstanding.get());
        assertTrue(slice.release());
        assertEquals(0, outstanding.get());
    }
}
