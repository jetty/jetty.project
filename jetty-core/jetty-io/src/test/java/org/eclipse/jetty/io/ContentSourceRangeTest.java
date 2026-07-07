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

import org.eclipse.jetty.io.content.AsyncContent;
import org.eclipse.jetty.io.content.ByteBufferContentSource;
import org.eclipse.jetty.io.internal.ContentSourceRange;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContentSourceRangeTest
{
    @Test
    public void testNullReturn()
    {
        Content.Source source = new ContentSourceRange(new AsyncContent(), 0, -1);
        assertNull(source.read());
    }

    @Test
    public void testInvalidArguments()
    {
        // Throws if the offset is negative.
        assertThrows(IndexOutOfBoundsException.class, () -> new ContentSourceRange(new AsyncContent(), -1, -1));

        // Throws if the offset is beyond the length of the source.
        ByteBufferContentSource source = new ByteBufferContentSource(BufferUtil.toBuffer("hello".getBytes()));
        assertThrows(IndexOutOfBoundsException.class, () -> new ContentSourceRange(source, 6, -1));
    }

    @Test
    public void testFullLength()
    {
        AsyncContent asyncContent = new AsyncContent();
        Content.Source source = new ContentSourceRange(asyncContent, 0, -1);

        asyncContent.write(false, BufferUtil.toReadableBuffer("A"), Callback.NOOP);
        asyncContent.write(false, BufferUtil.toReadableBuffer("B"), Callback.NOOP);
        asyncContent.write(true, ReadableBuffer.EMPTY, Callback.NOOP);

        Content.Chunk c1 = source.read();
        assertThat(c1.getByteBuffer(), equalTo(BufferUtil.toBuffer("A")));
        assertFalse(c1.isLast());
        c1.release();

        Content.Chunk c2 = source.read();
        assertThat(c2.getByteBuffer(), equalTo(BufferUtil.toBuffer("B")));
        assertFalse(c2.isLast());
        c2.release();

        Content.Chunk last = source.read();
        assertThat(last.remaining(), equalTo(0));
        assertTrue(last.isLast());
        last.release();
    }

    @Test
    public void testOffset()
    {
        AsyncContent asyncContent = new AsyncContent();
        Content.Source source = new ContentSourceRange(asyncContent, 5, -1);

        asyncContent.write(false, BufferUtil.toReadableBuffer("12"), Callback.NOOP);
        asyncContent.write(false, BufferUtil.toReadableBuffer("345"), Callback.NOOP);
        asyncContent.write(false, BufferUtil.toReadableBuffer("XYZ"), Callback.NOOP);
        asyncContent.write(true, ReadableBuffer.EMPTY, Callback.NOOP);

        Content.Chunk c1 = source.read();
        assertThat(c1.getByteBuffer(), equalTo(BufferUtil.toBuffer("XYZ")));
        assertFalse(c1.isLast());
        c1.release();

        Content.Chunk c2 = source.read();
        assertTrue(c2.isLast());
        assertThat(c2.remaining(), equalTo(0));
        c2.release();
    }

    @Test
    public void testOffsetMidChunk()
    {
        AsyncContent asyncContent = new AsyncContent();
        Content.Source source = new ContentSourceRange(asyncContent, 3, 2);

        asyncContent.write(true, BufferUtil.toReadableBuffer("abcdef"), Callback.NOOP);

        Content.Chunk c1 = source.read();
        assertTrue(c1.isLast());
        assertThat(c1.getByteBuffer(), equalTo(BufferUtil.toBuffer("de")));
        c1.release();
    }

    @Test
    public void testSpanMultipleChunks()
    {
        AsyncContent asyncContent = new AsyncContent();
        Content.Source source = new ContentSourceRange(asyncContent, 2, 8);

        asyncContent.write(false, BufferUtil.toReadableBuffer("AAAA"), Callback.NOOP);
        asyncContent.write(false, BufferUtil.toReadableBuffer("BBBB"), Callback.NOOP);
        asyncContent.write(true, BufferUtil.toReadableBuffer("CCCC"), Callback.NOOP);

        Content.Chunk c1 = source.read();
        assertThat(c1.getByteBuffer(), equalTo(BufferUtil.toBuffer("AA")));
        assertFalse(c1.isLast());
        c1.release();

        Content.Chunk c2 = source.read();
        assertThat(c2.getByteBuffer(), equalTo(BufferUtil.toBuffer("BBBB")));
        assertFalse(c2.isLast());
        c2.release();

        Content.Chunk c3 = source.read();
        assertThat(c3.getByteBuffer(), equalTo(BufferUtil.toBuffer("CC")));
        assertTrue(c3.isLast());
        c3.release();
    }

    @Test
    public void testZeroLengthRange()
    {
        AsyncContent asyncContent = new AsyncContent();
        Content.Source source = new ContentSourceRange(asyncContent, 0, 0);

        asyncContent.write(false, BufferUtil.toReadableBuffer("foo"), Callback.NOOP);
        asyncContent.write(false, BufferUtil.toReadableBuffer("bar"), Callback.NOOP);
        asyncContent.write(true, ReadableBuffer.EMPTY, Callback.NOOP);

        Content.Chunk c1 = source.read();
        assertTrue(c1.isLast());
        assertThat(c1.remaining(), equalTo(0));
        c1.release();
    }

    @Test
    public void testOffsetBeyondLength()
    {
        AsyncContent asyncContent = new AsyncContent();
        Content.Source source = new ContentSourceRange(asyncContent, 200, -1);

        asyncContent.write(false, BufferUtil.toReadableBuffer("hello"), Callback.NOOP);
        asyncContent.write(false, BufferUtil.toReadableBuffer("world"), Callback.NOOP);
        asyncContent.write(true, ReadableBuffer.EMPTY, Callback.NOOP);

        Content.Chunk c1 = source.read();
        assertTrue(c1.isLast());
        assertThat(c1.remaining(), equalTo(0));
        c1.release();
    }

    @Test
    public void testFailureChunkBeforeEOF()
    {
        AsyncContent asyncContent = new AsyncContent();
        Content.Source source = new ContentSourceRange(asyncContent, 5, 5);

        asyncContent.write(false, BufferUtil.toReadableBuffer("hello"), Callback.NOOP);
        asyncContent.write(false, BufferUtil.toReadableBuffer("world"), Callback.NOOP);

        Content.Chunk c1 = source.read();
        assertFalse(c1.isLast());
        assertThat(c1.getByteBuffer(), equalTo(BufferUtil.toBuffer("world")));
        c1.release();

        // We have read the full range, but trying to read to EOF we can still get a failure.
        asyncContent.fail(new RuntimeException("test exception"));

        Content.Chunk c2 = source.read();
        assertTrue(c2.isLast());
        assertThat(c2.getFailure(), instanceOf(RuntimeException.class));
        c2.release();
    }

    @Test
    public void testFailureBeforeStartingOffset()
    {
        AsyncContent asyncContent = new AsyncContent();
        Content.Source source = new ContentSourceRange(asyncContent, 20, 5);

        asyncContent.write(false, BufferUtil.toReadableBuffer("hello"), Callback.NOOP);
        asyncContent.write(false, BufferUtil.toReadableBuffer("world"), Callback.NOOP);
        asyncContent.fail(new RuntimeException("test exception"));

        Content.Chunk c1 = source.read();
        assertTrue(c1.isLast());
        assertTrue(c1.isEmpty());
        assertThat(c1.getFailure(), instanceOf(RuntimeException.class));
        assertThat(c1.getFailure().getMessage(), equalTo("test exception"));

        c1.release();
    }
}