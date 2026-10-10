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
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
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

        asyncContent.write(false, RetainableByteBuffer.wrap("A", ISO_8859_1), Callback.NOOP);
        asyncContent.write(false, RetainableByteBuffer.wrap("B", ISO_8859_1), Callback.NOOP);
        asyncContent.write(true, RetainableByteBuffer.empty(), Callback.NOOP);

        try (Content.Chunk c1 = source.read())
        {
            try (RetainableByteBuffer buffer = c1.acquire())
            {
                assertThat(buffer.getString(ISO_8859_1), is("A"));
                assertFalse(c1.isLast());
            }
        }

        try (Content.Chunk c2 = source.read())
        {
            try (RetainableByteBuffer buffer = c2.acquire())
            {
                assertThat(buffer.getString(ISO_8859_1), is("B"));
                assertFalse(c2.isLast());
            }
        }

        try (Content.Chunk last = source.read())
        {
            assertThat(last.remaining(), equalTo(0L));
            assertTrue(last.isLast());
        }
    }

    @Test
    public void testOffset()
    {
        AsyncContent asyncContent = new AsyncContent();
        Content.Source source = new ContentSourceRange(asyncContent, 5, -1);

        asyncContent.write(false, RetainableByteBuffer.wrap("12", ISO_8859_1), Callback.NOOP);
        asyncContent.write(false, RetainableByteBuffer.wrap("345", ISO_8859_1), Callback.NOOP);
        asyncContent.write(false, RetainableByteBuffer.wrap("XYZ", ISO_8859_1), Callback.NOOP);
        asyncContent.write(true, RetainableByteBuffer.empty(), Callback.NOOP);

        try (Content.Chunk c1 = source.read())
        {
            try (RetainableByteBuffer buffer = c1.acquire())
            {
                assertThat(buffer.getString(ISO_8859_1), is("XYZ"));
                assertFalse(c1.isLast());
            }
        }

        try (Content.Chunk c2 = source.read())
        {
            assertTrue(c2.isLast());
            assertThat(c2.remaining(), equalTo(0L));
        }
    }

    @Test
    public void testOffsetMidChunk()
    {
        AsyncContent asyncContent = new AsyncContent();
        Content.Source source = new ContentSourceRange(asyncContent, 3, 2);

        asyncContent.write(true, RetainableByteBuffer.wrap("abcdef", ISO_8859_1), Callback.NOOP);

        try (Content.Chunk c1 = source.read())
        {
            try (RetainableByteBuffer buffer = c1.acquire())
            {
                assertThat(buffer.getString(ISO_8859_1), is("de"));
                assertTrue(c1.isLast());
            }
        }
    }

    @Test
    public void testSpanMultipleChunks()
    {
        AsyncContent asyncContent = new AsyncContent();
        Content.Source source = new ContentSourceRange(asyncContent, 2, 8);

        asyncContent.write(false, RetainableByteBuffer.wrap("AAAA", ISO_8859_1), Callback.NOOP);
        asyncContent.write(false, RetainableByteBuffer.wrap("BBBB", ISO_8859_1), Callback.NOOP);
        asyncContent.write(true, RetainableByteBuffer.wrap("CCCC", ISO_8859_1), Callback.NOOP);

        try (Content.Chunk c1 = source.read())
        {
            try (RetainableByteBuffer buffer = c1.acquire())
            {
                assertThat(buffer.getString(ISO_8859_1), is("AA"));
                assertFalse(c1.isLast());
            }
        }

        try (Content.Chunk c2 = source.read())
        {
            try (RetainableByteBuffer buffer = c2.acquire())
            {
                assertThat(buffer.getString(ISO_8859_1), is("BBBB"));
                assertFalse(c2.isLast());
            }
        }

        try (Content.Chunk c3 = source.read())
        {
            try (RetainableByteBuffer buffer = c3.acquire())
            {
                assertThat(buffer.getString(ISO_8859_1), is("CC"));
                assertTrue(c3.isLast());
            }
        }
    }

    @Test
    public void testZeroLengthRange()
    {
        AsyncContent asyncContent = new AsyncContent();
        Content.Source source = new ContentSourceRange(asyncContent, 0, 0);

        asyncContent.write(false, RetainableByteBuffer.wrap("foo", ISO_8859_1), Callback.NOOP);
        asyncContent.write(false, RetainableByteBuffer.wrap("bar", ISO_8859_1), Callback.NOOP);
        asyncContent.write(true, RetainableByteBuffer.empty(), Callback.NOOP);

        try (Content.Chunk c1 = source.read())
        {
            assertTrue(c1.isLast());
            assertThat(c1.remaining(), equalTo(0L));
        }
    }

    @Test
    public void testOffsetBeyondLength()
    {
        AsyncContent asyncContent = new AsyncContent();
        Content.Source source = new ContentSourceRange(asyncContent, 200, -1);

        asyncContent.write(false, RetainableByteBuffer.wrap("hello", ISO_8859_1), Callback.NOOP);
        asyncContent.write(false, RetainableByteBuffer.wrap("world", ISO_8859_1), Callback.NOOP);
        asyncContent.write(true, RetainableByteBuffer.empty(), Callback.NOOP);

        try (Content.Chunk c1 = source.read())
        {
            assertTrue(c1.isLast());
            assertThat(c1.remaining(), equalTo(0L));
        }
    }

    @Test
    public void testFailureChunkBeforeEOF()
    {
        AsyncContent asyncContent = new AsyncContent();
        Content.Source source = new ContentSourceRange(asyncContent, 5, 5);

        asyncContent.write(false, RetainableByteBuffer.wrap("hello", ISO_8859_1), Callback.NOOP);
        asyncContent.write(false, RetainableByteBuffer.wrap("world", ISO_8859_1), Callback.NOOP);

        try (Content.Chunk c1 = source.read())
        {
            try (RetainableByteBuffer buffer = c1.acquire())
            {
                assertThat(buffer.getString(ISO_8859_1), is("world"));
                assertFalse(c1.isLast());
            }
        }

        // We have read the full range, but trying to read to EOF we can still get a failure.
        asyncContent.fail(new RuntimeException("test exception"));

        try (Content.Chunk c2 = source.read())
        {
            assertThat(c2.getFailure(), instanceOf(RuntimeException.class));
            assertTrue(c2.isLast());
        }
    }

    @Test
    public void testFailureBeforeStartingOffset()
    {
        AsyncContent asyncContent = new AsyncContent();
        Content.Source source = new ContentSourceRange(asyncContent, 20, 5);

        asyncContent.write(false, RetainableByteBuffer.wrap("hello", ISO_8859_1), Callback.NOOP);
        asyncContent.write(false, RetainableByteBuffer.wrap("world", ISO_8859_1), Callback.NOOP);
        asyncContent.fail(new RuntimeException("test exception"));

        try (Content.Chunk c1 = source.read())
        {
            assertTrue(c1.isLast());
            assertFalse(c1.hasRemaining());
            assertThat(c1.getFailure(), instanceOf(RuntimeException.class));
            assertThat(c1.getFailure().getMessage(), equalTo("test exception"));
        }
    }
}