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
import java.nio.ReadOnlyBufferException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SingleMutableBufferTest
{
    @Test
    public void testDisplaceWritePosition()
    {
        RetainableByteBuffer.Mutable b = RetainableByteBuffer.Mutable.allocate(10, false);
        b.putInt(1);
        assertEquals(4, b.writePosition());
        assertEquals(1, b.getInt());
        assertEquals(4, b.writePosition());
        assertEquals(4, b.readPosition());
        assertEquals(0, b.remaining());
        assertThrows(BufferUnderflowException.class, b::getInt);

        // Pad with 4 bytes: it's equivalent to a putInt().
        b.pad(4);
        assertEquals(0, b.getInt());
    }

    @Test
    public void testReadPositionAndUnderflow()
    {
        RetainableByteBuffer b = RetainableByteBuffer.wrap(ByteBuffer.allocate(10)
            .putInt(1)
            .putInt(2)
            .flip()
        );
        assertEquals(0L, b.readPosition());
        assertEquals(8L, b.remaining());
        assertEquals(1, b.getInt());

        assertEquals(4L, b.readPosition());
        assertEquals(4L, b.remaining());
        assertEquals(2, b.getInt());

        assertThrows(BufferUnderflowException.class, b::getInt);

        b.readPosition(4L);
        assertEquals(4L, b.readPosition());
        assertEquals(4L, b.remaining());
        assertEquals(2, b.getInt());
    }

    @Test
    public void testWritePositionAndOverflow()
    {
        RetainableByteBuffer.Mutable b = RetainableByteBuffer.Mutable.allocate(10, false);
        assertEquals(0L, b.writePosition());
        assertEquals(10L, b.space());
        b.putInt(1);
        b.putInt(2);

        assertEquals(8L, b.writePosition());
        assertEquals(2L, b.space());

        assertThrows(BufferOverflowException.class, () -> b.putInt(1));
        assertEquals(8L, b.writePosition());
        b.putShort((short)100);
        assertEquals(10L, b.writePosition());
        assertEquals(0L, b.space());
    }

    @Test
    public void testReadPositionAndWritePosition()
    {
        RetainableByteBuffer.Mutable b = RetainableByteBuffer.Mutable.allocate(10, false);
        b.putInt(1);
        b.putInt(2);

        assertEquals(8L, b.writePosition());
        assertEquals(2L, b.space());

        assertEquals(0L, b.readPosition());
        assertEquals(8L, b.remaining());

        assertEquals(8L, b.writePosition());
        assertEquals(2L, b.space());

        assertEquals(1, b.getInt());
        assertEquals(4L, b.readPosition());
        assertEquals(4L, b.remaining());
    }

    @Test
    public void testReadFrom() throws Exception
    {
        RetainableByteBuffer.Mutable b = RetainableByteBuffer.Mutable.allocate(10, false);

        long read = b.readFrom(output ->
        {
            output.put((byte)1);
            output.put((byte)2);
            output.put((byte)3);
            output.put((byte)4);
            return 4;
        });
        assertEquals(4L, read);

        assertEquals(4L, b.remaining());
        assertEquals((byte)1, b.get());
        assertEquals((byte)2, b.get());
        assertEquals((byte)3, b.get());
        assertEquals((byte)4, b.get());
        assertEquals(0L, b.remaining());

        read = b.readFrom(_ -> -1);
        assertEquals(-1L, read);
    }

    @Test
    public void testWriteTo() throws Exception
    {
        RetainableByteBuffer.Mutable b = RetainableByteBuffer.Mutable.wrap(ByteBuffer.allocate(14)
            .putInt(1)
            .putInt(2)
            .putInt(3)
        );

        List<ByteBuffer> writtenByteBuffers = new ArrayList<>();
        long written = b.writeTo(input ->
        {
            long result = 0;
            assertEquals(0, input.position());
            assertEquals(12, input.remaining());
            assertEquals(1, input.getInt());
            result += Integer.BYTES;
            assertEquals(2, input.getInt());
            result += Integer.BYTES;
            assertEquals(8, input.position());
            assertEquals(4, input.remaining());
            writtenByteBuffers.add(input);
            return result;
        });
        assertEquals(8, written);
        assertEquals(1, writtenByteBuffers.size());
        assertEquals(4, b.remaining());
        assertEquals(8, b.readPosition());

        assertEquals(2, b.space());
        assertEquals(12, b.writePosition());
    }

    @Test
    public void testCompact()
    {
        RetainableByteBuffer.Mutable b = RetainableByteBuffer.Mutable.wrap(ByteBuffer.allocate(10));
        assertEquals(0, b.readPosition());
        assertEquals(0, b.remaining());

        b.compact();
        assertEquals(0, b.readPosition());
        assertEquals(0, b.remaining());

        b.putInt(1);
        b.putShort((short)2);

        assertEquals(0, b.readPosition());
        assertEquals(6, b.remaining());

        b.compact();
        assertEquals(0, b.readPosition());
        assertEquals(6, b.remaining());
        assertEquals(1, b.getInt());
        assertEquals(4, b.readPosition());
        assertEquals(2, b.remaining());

        b.compact();
        assertEquals(0, b.readPosition());
        assertEquals(2, b.remaining());
        assertEquals(2, b.getShort());

        b.compact();
        assertEquals(0, b.readPosition());
        assertEquals(0, b.remaining());
    }

    @Test
    public void testMutableWrapReadOnly()
    {
        assertThrows(ReadOnlyBufferException.class, () -> RetainableByteBuffer.Mutable.wrap(ByteBuffer.allocate(10).asReadOnlyBuffer()));
    }

    @Test
    public void testRetainableByteBufferWrap()
    {
        RetainableByteBuffer.Mutable b = RetainableByteBuffer.Mutable.wrap(ByteBuffer.allocate(10)
            .putInt(1)
            .putInt(2)
        );
        assertEquals(0L, b.readPosition());
        assertEquals(8L, b.remaining());
        assertEquals(1, b.getInt());
        assertEquals(2, b.getInt());
        assertEquals(0L, b.remaining());

        assertEquals(8L, b.writePosition());
        assertEquals(2L, b.space());
    }

    @Test
    public void testMutableWrap()
    {
        RetainableByteBuffer.Mutable b = RetainableByteBuffer.Mutable.wrap(ByteBuffer.allocate(10));
        assertEquals(0L, b.writePosition());
        assertEquals(10L, b.space());

        b.putInt(1);
        b.putInt(2);
        assertEquals(8L, b.writePosition());
        assertEquals(2L, b.space());

        assertEquals(1, b.getInt());
        assertEquals(2, b.getInt());
        assertEquals(2L, b.space());
    }

    @Test
    public void testMutableAllocate()
    {
        RetainableByteBuffer.Mutable b = RetainableByteBuffer.Mutable.allocate(10, false);
        assertEquals(0L, b.writePosition());
        assertEquals(10L, b.space());

        b.putInt(1);
        b.putInt(2);
        assertEquals(8L, b.writePosition());
        assertEquals(2L, b.space());

        assertEquals(1, b.getInt());
        assertEquals(2, b.getInt());
        assertEquals(2L, b.space());
    }

    @Test
    public void testPutsGets()
    {
        RetainableByteBuffer data = RetainableByteBuffer.wrap(ByteBuffer.allocate(3)
            .put((byte)1)
            .put((byte)2)
            .put((byte)3)
            .flip()
        );
        assertEquals(3, data.remaining());
        assertEquals(3, data.capacity());

        RetainableByteBuffer.Mutable b = RetainableByteBuffer.Mutable.allocate(20, false);
        assertEquals(20, b.capacity());

        b.put((byte)1);
        b.putShort((short)2);
        b.putInt(4);
        b.putLong(8);
        b.put(data);
        assertEquals(0, data.remaining());
        assertEquals(3, data.capacity());
        assertEquals(2, b.space());
        assertEquals(20, b.capacity());

        assertEquals((byte)1, b.get());
        assertEquals((short)2, b.getShort());
        assertEquals(4, b.getInt());
        assertEquals(8, b.getLong());
        byte[] bytes = new byte[3];
        b.get(bytes);
        assertEquals((byte)1, bytes[0]);
        assertEquals((byte)2, bytes[1]);
        assertEquals((byte)3, bytes[2]);
        assertEquals(2, b.space());
        b.compact();
        assertEquals(20, b.space());
    }

    @Test
    public void testSlice()
    {
        RetainableByteBuffer b = RetainableByteBuffer.wrap(ByteBuffer.allocate(20)
            .putInt(1)
            .putInt(2)
            .putInt(3)
            .flip()
        );

        assertEquals(20, b.capacity());
        assertEquals(0, b.readPosition());
        assertEquals(12, b.remaining());

        assertEquals(1, b.getInt());
        assertEquals(4, b.readPosition());
        assertEquals(8, b.remaining());

        RetainableByteBuffer sb1 = b.slice();
        assertEquals(8, sb1.capacity());
        assertEquals(0, sb1.readPosition());
        assertEquals(8, sb1.remaining());

        assertEquals(2, b.getInt());
        assertEquals(8, b.readPosition());
        assertEquals(4, b.remaining());

        assertEquals(2, sb1.getInt());
        assertEquals(4, sb1.readPosition());
        assertEquals(4, sb1.remaining());

        RetainableByteBuffer sb2 = sb1.slice();
        assertEquals(4, sb2.capacity());
        assertEquals(0, sb2.readPosition());
        assertEquals(4, sb2.remaining());

        assertEquals(3, sb2.getInt());
        assertEquals(4, sb2.readPosition());
        assertEquals(0, sb2.remaining());

        assertEquals(3, sb1.getInt());
        assertEquals(8, sb1.readPosition());
        assertEquals(0, sb1.remaining());

        assertEquals(3, b.getInt());
        assertEquals(12, b.readPosition());
        assertEquals(0, b.remaining());

        assertFalse(sb2.release());
        assertFalse(sb1.release());
        assertTrue(b.release());
    }

    @Test
    public void testSlicePositionLength()
    {
        RetainableByteBuffer b = RetainableByteBuffer.wrap(ByteBuffer.allocate(20)
            .putInt(1)
            .putInt(2)
            .putInt(3)
            .flip()
        );

        assertEquals(20, b.capacity());
        assertEquals(0, b.readPosition());
        assertEquals(12, b.remaining());

        assertEquals(1, b.getInt());
        assertEquals(4, b.readPosition());
        assertEquals(8, b.remaining());

        RetainableByteBuffer sb1 = b.slice(8, 4);
        assertEquals(4, sb1.capacity());
        assertEquals(0, sb1.readPosition());
        assertEquals(4, sb1.remaining());

        assertEquals(3, sb1.getInt());

        assertFalse(sb1.release());
        assertTrue(b.release());
    }

    @Test
    public void testEmpty()
    {
        assertThrows(BufferUnderflowException.class, RetainableByteBuffer.empty()::get);
        assertThrows(BufferOverflowException.class, () -> RetainableByteBuffer.Mutable.empty().put((byte)1));
    }
}
