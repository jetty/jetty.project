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

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FixedSizeBufferTest
{
    @Test
    public void testToReadableAfterRewindingPositionInWriteBuffer()
    {
        WritableBuffer wb = WritableBuffer.allocate(10, false);
        wb.putInt(1);
        ReadableBuffer rb = wb.toReadable();
        assertEquals(1, rb.getInt());
        rb.toWritable();
        wb.position(2); // rewind write position
        wb.toReadable();
        assertEquals(2, rb.position());
        assertEquals(0, rb.remaining());
    }

    @Test
    public void testReadableBufferPositionAndUnderflow()
    {
        ReadableBuffer rb = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(1)
            .putInt(2)
            .flip());
        assertEquals(0L, rb.position());
        assertEquals(8L, rb.remaining());
        assertEquals(1, rb.getInt());

        assertEquals(4L, rb.position());
        assertEquals(4L, rb.remaining());
        assertEquals(2, rb.getInt());

        assertThrows(BufferUnderflowException.class, rb::getInt);

        rb.position(4L);
        assertEquals(4L, rb.position());
        assertEquals(4L, rb.remaining());
        assertEquals(2, rb.getInt());
    }

    @Test
    public void testWritableBufferPositionAndOverflow()
    {
        WritableBuffer wb = WritableBuffer.allocate(10, false);
        assertEquals(0L, wb.position());
        assertEquals(10L, wb.remaining());
        wb.putInt(1);
        wb.putInt(2);

        assertEquals(8L, wb.position());
        assertEquals(2L, wb.remaining());

        assertThrows(BufferOverflowException.class, () -> wb.putInt(1));
        assertEquals(8L, wb.position());
        wb.putShort((short)100);
        assertEquals(10L, wb.position());
        assertEquals(0L, wb.remaining());

        wb.position(4L);
        assertEquals(4L, wb.position());
        assertEquals(6L, wb.remaining());

        wb.putInt(22);
        assertEquals(8L, wb.position());
        assertEquals(2L, wb.remaining());
    }

    @Test
    public void testAsReadableAsWritablePosition()
    {
        WritableBuffer wb = WritableBuffer.allocate(10, false);
        wb.putInt(1);
        wb.putInt(2);

        assertEquals(8L, wb.position());
        assertEquals(2L, wb.remaining());

        ReadableBuffer rb = wb.toReadable();
        assertEquals(0L, rb.position());
        assertEquals(8L, rb.remaining());

        WritableBuffer wb2 = rb.toWritable();
        assertEquals(8L, wb2.position());
        assertEquals(2L, wb2.remaining());

        ReadableBuffer rb2 = wb2.toReadable();
        assertEquals(1, rb2.getInt());
        assertEquals(4L, rb2.position());
        assertEquals(4L, rb2.remaining());
    }

    @Test
    public void testReadFrom() throws Exception
    {
        WritableBuffer wb = WritableBuffer.allocate(10, false);

        long read = wb.readFrom(output ->
        {
            output.put((byte)1);
            output.put((byte)2);
            output.put((byte)3);
            output.put((byte)4);
            return true;
        });
        assertEquals(4L, read);

        ReadableBuffer rb = wb.toReadable();
        assertEquals(4L, rb.remaining());
        assertEquals((byte)1, rb.get());
        assertEquals((byte)2, rb.get());
        assertEquals((byte)3, rb.get());
        assertEquals((byte)4, rb.get());
        assertEquals(0L, rb.remaining());

        wb = rb.toWritable();
        read = wb.readFrom(output -> true);
        assertEquals(-1L, read);
    }

    @Test
    public void testWriteTo() throws Exception
    {
        ReadableBuffer rb = ReadableBuffer.wrap(ByteBuffer.allocate(14)
            .putInt(1)
            .putInt(2)
            .putInt(3)
            .flip());

        List<ByteBuffer> writtenByteBuffers = new ArrayList<>();
        long written = rb.writeTo(input ->
        {
            assertEquals(0, input.position());
            assertEquals(12, input.remaining());
            assertEquals(1, input.getInt());
            assertEquals(2, input.getInt());
            assertEquals(8, input.position());
            assertEquals(4, input.remaining());
            writtenByteBuffers.add(input);
        });
        assertEquals(8, written);
        assertEquals(1, writtenByteBuffers.size());
        assertEquals(4, rb.remaining());
        assertEquals(8, rb.position());
        WritableBuffer wb = rb.compact();
        assertEquals(10, wb.remaining());
        assertEquals(4, wb.position());
    }

    @Test
    public void testCompact()
    {
        ReadableBuffer rb = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .flip());
        assertEquals(0, rb.position());
        assertEquals(0, rb.remaining());

        WritableBuffer wb = rb.compact();
        assertEquals(0, rb.position());
        assertEquals(10, rb.remaining());

        wb.putInt(1);
        wb.putShort((short)2);

        ReadableBuffer rb1 = wb.toReadable();
        assertEquals(0, rb1.position());
        assertEquals(6, rb1.remaining());

        rb1.compact().toReadable();
        assertEquals(0, rb1.position());
        assertEquals(6, rb1.remaining());
        assertEquals(1, rb1.getInt());
        assertEquals(4, rb1.position());
        assertEquals(2, rb1.remaining());

        rb1.compact().toReadable();
        assertEquals(0, rb1.position());
        assertEquals(2, rb1.remaining());
        assertEquals(2, rb1.getShort());

        rb1.compact().toReadable();
        assertEquals(0, rb1.position());
        assertEquals(0, rb1.remaining());
    }

    @Test
    public void testReadableBufferDrain()
    {
        ReadableBuffer rb = ReadableBuffer.wrap(ByteBuffer.allocate(20)
            .putInt(1)
            .putInt(2)
            .putInt(3)
            .flip());

        assertEquals(12, rb.remaining());
        assertEquals(1, rb.getInt());
        assertEquals(2, rb.getInt());
        assertEquals(4, rb.remaining());

        rb.drain();
        assertEquals(0, rb.position());
        assertEquals(0, rb.remaining());

        WritableBuffer wb = rb.toWritable();
        assertEquals(0, wb.position());
        assertEquals(20, wb.remaining());
    }

    @Test
    public void testReadableBufferWrapReadOnly()
    {
        ReadableBuffer rb = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(1)
            .putInt(2)
            .flip()
            .asReadOnlyBuffer());
        assertEquals(0L, rb.position());
        assertEquals(8L, rb.remaining());
        assertEquals(1, rb.getInt());
        assertEquals(2, rb.getInt());
        assertEquals(0L, rb.remaining());

        assertThrows(IllegalStateException.class, rb::toWritable);
    }

    @Test
    public void testReadableBufferWrap()
    {
        ReadableBuffer rb = ReadableBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(1)
            .putInt(2)
            .flip());
        assertEquals(0L, rb.position());
        assertEquals(8L, rb.remaining());
        assertEquals(1, rb.getInt());
        assertEquals(2, rb.getInt());
        assertEquals(0L, rb.remaining());

        WritableBuffer wb = rb.toWritable();
        assertEquals(8L, wb.position());
        assertEquals(2L, wb.remaining());
    }

    @Test
    public void testWritableBufferWrap()
    {
        WritableBuffer wb = WritableBuffer.wrap(ByteBuffer.allocate(10));
        assertEquals(0L, wb.position());
        assertEquals(10L, wb.remaining());

        wb.putInt(1);
        wb.putInt(2);
        assertEquals(8L, wb.position());
        assertEquals(2L, wb.remaining());

        ReadableBuffer rb = wb.toReadable();
        assertEquals(1, rb.getInt());
        assertEquals(2, rb.getInt());
        assertEquals(0L, wb.remaining());
    }

    @Test
    public void testWritableBufferAllocate()
    {
        WritableBuffer wb = WritableBuffer.allocate(10, false);
        assertEquals(0L, wb.position());
        assertEquals(10L, wb.remaining());

        wb.putInt(1);
        wb.putInt(2);
        assertEquals(8L, wb.position());
        assertEquals(2L, wb.remaining());

        ReadableBuffer rb = wb.toReadable();
        assertEquals(1, rb.getInt());
        assertEquals(2, rb.getInt());
        assertEquals(0L, wb.remaining());
    }

    @Test
    public void testReadModeChecks()
    {
        WritableBuffer wb = WritableBuffer.allocate(10, false);
        wb.toReadable();

        assertThrows(IllegalStateException.class, () -> wb.put((byte)1));
        assertThrows(IllegalStateException.class, () -> wb.putShort((short)1));
        assertThrows(IllegalStateException.class, () -> wb.putInt(1));
        assertThrows(IllegalStateException.class, () -> wb.putLong(1L));
        assertThrows(IllegalStateException.class, () -> wb.put(ReadableBuffer.wrap(ByteBuffer.allocate(1).flip())));
        assertThrows(IllegalStateException.class, wb::toReadable);
        assertThrows(IllegalStateException.class, () -> wb.readFrom(b -> false));
    }

    @Test
    public void testWriteModeChecks()
    {
        ReadableBuffer rb = ReadableBuffer.wrap(ByteBuffer.allocate(10).flip());
        rb.toWritable();

        assertThrows(IllegalStateException.class, rb::get);
        assertThrows(IllegalStateException.class, rb::getShort);
        assertThrows(IllegalStateException.class, rb::getInt);
        assertThrows(IllegalStateException.class, rb::getLong);
        assertThrows(IllegalStateException.class, rb::compact);
        assertThrows(IllegalStateException.class, rb::toWritable);
        assertThrows(IllegalStateException.class, rb::slice);
        assertThrows(IllegalStateException.class, () -> rb.slice(1L, 2L));
        assertThrows(IllegalStateException.class, () -> rb.writeTo(b ->
        {}));
    }

    @Test
    public void testPutsGets()
    {
        ReadableBuffer dataRb = ReadableBuffer.wrap(ByteBuffer.allocate(3)
            .put((byte)1)
            .put((byte)2)
            .put((byte)3)
            .flip());
        assertEquals(3, dataRb.remaining());
        assertEquals(3, dataRb.capacity());

        WritableBuffer wb = WritableBuffer.allocate(20, false);
        assertEquals(20, wb.capacity());

        wb.put((byte)1);
        wb.putShort((short)2);
        wb.putInt(4);
        wb.putLong(8);
        wb.put(dataRb);
        assertEquals(0, dataRb.remaining());
        assertEquals(3, dataRb.capacity());
        assertEquals(2, wb.remaining());
        assertEquals(20, wb.capacity());

        ReadableBuffer rb = wb.toReadable();
        assertEquals(18, wb.remaining());

        assertEquals((byte)1, rb.get());
        assertEquals((short)2, rb.getShort());
        assertEquals(4, rb.getInt());
        assertEquals(8, rb.getLong());
        assertEquals((byte)1, rb.get());
        assertEquals((byte)2, rb.get());
        assertEquals((byte)3, rb.get());
        assertEquals(0, wb.remaining());
    }

    @Test
    public void testSlice()
    {
        ReadableBuffer rb = ReadableBuffer.wrap(ByteBuffer.allocate(20)
            .putInt(1)
            .putInt(2)
            .putInt(3)
            .flip());

        assertEquals(20, rb.capacity());
        assertEquals(0, rb.position());
        assertEquals(12, rb.remaining());

        assertEquals(1, rb.getInt());
        assertEquals(4, rb.position());
        assertEquals(8, rb.remaining());

        ReadableBuffer srb1 = rb.slice();
        assertEquals(8, srb1.capacity());
        assertEquals(0, srb1.position());
        assertEquals(8, srb1.remaining());

        assertEquals(2, rb.getInt());
        assertEquals(8, rb.position());
        assertEquals(4, rb.remaining());

        assertEquals(2, srb1.getInt());
        assertEquals(4, srb1.position());
        assertEquals(4, srb1.remaining());

        ReadableBuffer srb2 = srb1.slice();
        assertEquals(4, srb2.capacity());
        assertEquals(0, srb2.position());
        assertEquals(4, srb2.remaining());

        assertEquals(3, srb2.getInt());
        assertEquals(4, srb2.position());
        assertEquals(0, srb2.remaining());

        assertEquals(3, srb1.getInt());
        assertEquals(8, srb1.position());
        assertEquals(0, srb1.remaining());

        assertEquals(3, rb.getInt());
        assertEquals(12, rb.position());
        assertEquals(0, rb.remaining());

        assertFalse(srb2.release());
        assertFalse(srb1.release());
        assertTrue(rb.release());
    }

    @Test
    public void testSlicePositionLength()
    {
        ReadableBuffer rb = ReadableBuffer.wrap(ByteBuffer.allocate(20)
            .putInt(1)
            .putInt(2)
            .putInt(3)
            .flip());

        assertEquals(20, rb.capacity());
        assertEquals(0, rb.position());
        assertEquals(12, rb.remaining());

        assertEquals(1, rb.getInt());
        assertEquals(4, rb.position());
        assertEquals(8, rb.remaining());

        ReadableBuffer srb1 = rb.slice(8, 4);
        assertEquals(4, srb1.capacity());
        assertEquals(0, srb1.position());
        assertEquals(4, srb1.remaining());

        assertEquals(3, srb1.getInt());

        assertFalse(srb1.release());
        assertTrue(rb.release());
    }

    @Test
    public void testEmpty()
    {
        assertThrows(IllegalStateException.class, ReadableBuffer.EMPTY::toWritable);
        assertThrows(BufferUnderflowException.class, ReadableBuffer.EMPTY::get);

        assertThrows(IllegalStateException.class, WritableBuffer.EMPTY::toReadable);
        assertThrows(BufferOverflowException.class, () -> WritableBuffer.EMPTY.put((byte)1));
    }
}
