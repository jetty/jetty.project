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

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;
import org.eclipse.jetty.util.buffer.WritableBufferPool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(WorkDirExtension.class)
public class PathReadableBufferTest
{
    public WorkDir workDir;

    @Test
    public void testRelativeGets() throws Exception
    {
        Path targetFile = workDir.getEmptyPathDir().resolve("testGetInt.txt");
        Files.writeString(targetFile, "    aaaa", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        ReadableBuffer rb = ReadableBuffer.wrap(targetFile, WritableBufferPool.SIZED_NON_POOLING);
        assertEquals(8L, rb.remaining());
        assertEquals(0L, rb.position());

        assertEquals(0x20202020, rb.getInt());
        assertEquals(4L, rb.remaining());
        assertEquals(4L, rb.position());

        assertEquals(0x61616161, rb.getInt());
        assertEquals(0L, rb.remaining());
        assertEquals(8L, rb.position());

        rb.position(5L);
        assertEquals(3L, rb.remaining());
        assertEquals(5L, rb.position());

        assertEquals(0x6161, rb.getShort());
        assertEquals(1L, rb.remaining());
        assertEquals(7L, rb.position());

        assertEquals(0x61, rb.get());
        assertEquals(0L, rb.remaining());
        assertEquals(8L, rb.position());

        assertThrows(BufferUnderflowException.class, rb::get);

        rb.position(7L);
        assertThrows(BufferUnderflowException.class, rb::getShort);
        assertEquals(7L, rb.position());

        rb.release();
    }

    @Test
    public void testAbsoluteGets() throws Exception
    {
        Path targetFile = workDir.getEmptyPathDir().resolve("testGetInt.txt");
        Files.writeString(targetFile, "    aaaa", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        ReadableBuffer rb = ReadableBuffer.wrap(targetFile, WritableBufferPool.SIZED_NON_POOLING);
        assertEquals(8L, rb.remaining());

        assertEquals(0x20202020, rb.getInt(0L));
        assertEquals(8L, rb.remaining());

        assertEquals(0x61616161, rb.getInt(4L));
        assertEquals(8L, rb.remaining());

        rb.position(5L);
        assertEquals(3L, rb.remaining());

        assertEquals(0x6161, rb.getShort(5L));
        assertEquals(3L, rb.remaining());

        assertEquals(0x61, rb.get(7L));
        assertEquals(3L, rb.remaining());

        assertThrows(BufferUnderflowException.class, () -> rb.getShort(7L));
        assertEquals(5L, rb.position());

        rb.release();
    }

    @Test
    public void testGetBytes() throws Exception
    {
        Path testResourcePathFile = MavenTestingUtils.getTestResourcePathFile("resource.txt");
        ReadableBuffer rb = ReadableBuffer.wrap(testResourcePathFile, WritableBufferPool.SIZED_NON_POOLING);

        byte[] bytes = new byte[Math.toIntExact(rb.remaining())];
        rb.get(bytes);
        assertEquals(0L, rb.remaining());
        assertEquals(20L, rb.position());
        assertEquals("This is a text file\n", new String(bytes));

        rb.release();
    }

    @Test
    public void testSlice() throws Exception
    {
        Path testResourcePathFile = MavenTestingUtils.getTestResourcePathFile("resource.txt");
        ReadableBuffer rb = ReadableBuffer.wrap(testResourcePathFile, WritableBufferPool.SIZED_NON_POOLING);

        byte[] bytes = new byte[Math.toIntExact(rb.remaining())];
        rb.get(bytes);
        assertEquals(0L, rb.remaining());
        assertEquals("This is a text file\n", new String(bytes));

        ReadableBuffer slice = rb.slice();

        assertEquals(0L, slice.remaining());

        slice.release();
        rb.release();
    }

    @Test
    public void testSlice2Args() throws Exception
    {
        Path testResourcePathFile = MavenTestingUtils.getTestResourcePathFile("resource.txt");
        ReadableBuffer rb = ReadableBuffer.wrap(testResourcePathFile, WritableBufferPool.SIZED_NON_POOLING);

        assertEquals(20L, rb.remaining());

        ReadableBuffer slice = rb.slice(5L, 10L);
        assertEquals(0L, slice.position());
        assertEquals(10L, slice.remaining());
        byte[] bytes = new byte[10];
        slice.get(bytes);
        assertEquals("is a text ", new String(bytes));
        slice.release();

        rb.release();
    }

    @Test
    public void testWriteTo() throws Exception
    {
        Path emptyPathDir = workDir.getEmptyPathDir();
        Path targetFile = emptyPathDir.resolve("testWriteTo.txt");
        FileChannel targetFc = FileChannel.open(targetFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        Path testResourcePathFile = MavenTestingUtils.getTestResourcePathFile("resource.txt");
        ReadableBuffer rb = ReadableBuffer.wrap(testResourcePathFile, WritableBufferPool.SIZED_NON_POOLING);

        long totalWritten = 0L;
        for (int i = 0; i < 20; i++)
        {
            totalWritten += rb.writeTo(input ->
            {
                int written = targetFc.write(input.slice().limit(1));
                input.position(input.position() + written);
            });
        }
        assertEquals(20L, totalWritten);
        assertEquals(0L, rb.remaining());
        targetFc.close();
        rb.release();
        assertEquals("This is a text file\n", Files.readString(targetFile));
    }

    @Test
    public void testWriteToTransferring() throws Exception
    {
        Path emptyPathDir = workDir.getEmptyPathDir();
        Path targetFile = emptyPathDir.resolve("testWriteToTransferring.txt");
        FileChannel targetFc = FileChannel.open(targetFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

        Path testResourcePathFile = MavenTestingUtils.getTestResourcePathFile("resource.txt");
        ReadableBuffer rb = ReadableBuffer.wrap(testResourcePathFile, WritableBufferPool.SIZED_NON_POOLING);

        long written = rb.writeTo(new ReadableBuffer.TransferringTarget()
        {
            @Override
            public long write(FileChannel input, long position, long count) throws IOException
            {
                return input.transferTo(position, count, targetFc);
            }

            @Override
            public void write(ByteBuffer input) throws IOException
            {
                throw new IOException("Should not be called");
            }
        });
        assertEquals(20L, written);
        assertEquals(0L, rb.remaining());
        targetFc.close();
        rb.release();
        assertEquals("This is a text file\n", Files.readString(targetFile));
    }

    @Test
    public void testWriteToAccumulated() throws Exception
    {
        WritableBuffer wb = WritableBuffer.allocate(20, false);
        wb.putBytes("-- prefix --\n".getBytes(StandardCharsets.UTF_8));
        ReadableBuffer rb1 = wb.toReadable();

        Path testResourcePathFile = MavenTestingUtils.getTestResourcePathFile("resource.txt");
        ReadableBuffer rb2 = ReadableBuffer.wrap(testResourcePathFile, WritableBufferPool.SIZED_NON_POOLING);

        wb = WritableBuffer.allocate(20, false);
        wb.putBytes("-- suffix --\n".getBytes(StandardCharsets.UTF_8));
        ReadableBuffer rb3 = wb.toReadable();

        ReadableBuffer acc = ReadableBuffer.accumulate(rb1, rb2, rb3);

        StringBuilder sb = new StringBuilder();
        long written = acc.writeTo(input ->
        {
            String string = BufferUtil.toString(input, StandardCharsets.UTF_8);
            input.position(input.position() + string.length());
            sb.append(string);
        });
        assertEquals("-- prefix --\nThis is a text file\n-- suffix --\n".length(), written);
        assertEquals("-- prefix --\nThis is a text file\n-- suffix --\n", sb.toString());
    }
}
