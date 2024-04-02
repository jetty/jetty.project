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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

public class ContentTest
{
    @Test
    public void testAsReadOnly()
    {
        assertThat(Content.Chunk.EOF.asReadOnly(), sameInstance(Content.Chunk.EOF));
        assertThat(Content.Chunk.EMPTY.asReadOnly(), sameInstance(Content.Chunk.EMPTY));

        assertThat(Content.Chunk.from(BufferUtil.EMPTY_BUFFER, true).asReadOnly(), sameInstance(Content.Chunk.EOF));
        assertThat(Content.Chunk.from(BufferUtil.EMPTY_BUFFER, false).asReadOnly(), sameInstance(Content.Chunk.EMPTY));

        Content.Chunk failureChunk = Content.Chunk.from(new NumberFormatException());
        assertThat(failureChunk.asReadOnly(), sameInstance(failureChunk));

        Content.Chunk chunk = Content.Chunk.from(ByteBuffer.wrap(new byte[1]).asReadOnlyBuffer(), false);
        assertThat(chunk.asReadOnly(), sameInstance(chunk));

        Content.Chunk rwChunk = Content.Chunk.from(ByteBuffer.wrap("abc".getBytes(StandardCharsets.US_ASCII)), false);
        Content.Chunk roChunk = rwChunk.asReadOnly();
        assertThat(rwChunk, not(sameInstance(roChunk)));
        assertThat(BufferUtil.toString(rwChunk.getByteBuffer(), StandardCharsets.US_ASCII), equalTo(BufferUtil.toString(roChunk.getByteBuffer(), StandardCharsets.US_ASCII)));
    }

    @Test
    public void testFromEmptyByteBufferWithoutReleaser()
    {
        assertThat(Content.Chunk.from(ByteBuffer.wrap(new byte[0]), true), sameInstance(Content.Chunk.EOF));
        assertThat(Content.Chunk.from(ByteBuffer.wrap(new byte[0]), false), sameInstance(Content.Chunk.EMPTY));
    }

    @Test
    public void testFromEmptyByteBufferWithRunnableReleaser()
    {
        AtomicInteger counter1 = new AtomicInteger();
        assertThat(Content.Chunk.from(ByteBuffer.wrap(new byte[0]), true, counter1::incrementAndGet), sameInstance(Content.Chunk.EOF));
        assertThat(counter1.get(), is(1));

        AtomicInteger counter2 = new AtomicInteger();
        assertThat(Content.Chunk.from(ByteBuffer.wrap(new byte[0]), false, counter2::incrementAndGet), sameInstance(Content.Chunk.EMPTY));
        assertThat(counter2.get(), is(1));
    }

    @Test
    public void testFromEmptyByteBufferWithConsumerReleaser()
    {
        List<ByteBuffer> buffers = new ArrayList<>();

        ByteBuffer buffer1 = ByteBuffer.wrap(new byte[0]);
        assertThat(Content.Chunk.from(buffer1, true, buffers::add), sameInstance(Content.Chunk.EOF));
        assertThat(buffers.size(), is(1));
        assertThat(buffers.remove(0), sameInstance(buffer1));

        ByteBuffer buffer2 = ByteBuffer.wrap(new byte[0]);
        assertThat(Content.Chunk.from(buffer2, false, buffers::add), sameInstance(Content.Chunk.EMPTY));
        assertThat(buffers.size(), is(1));
        assertThat(buffers.remove(0), sameInstance(buffer2));
    }

    @Test
    public void testFromEmptyByteBufferWithRetainableReleaser()
    {
        Retainable.ReferenceCounter referenceCounter1 = new Retainable.ReferenceCounter();
        referenceCounter1.retain();
        assertThat(referenceCounter1.isRetained(), is(true));
        assertThat(Content.Chunk.asChunk(ByteBuffer.wrap(new byte[0]), true, referenceCounter1), sameInstance(Content.Chunk.EOF));
        assertThat(referenceCounter1.isRetained(), is(false));
        assertThat(referenceCounter1.release(), is(true));

        Retainable.ReferenceCounter referenceCounter2 = new Retainable.ReferenceCounter();
        referenceCounter2.retain();
        assertThat(referenceCounter2.isRetained(), is(true));
        assertThat(Content.Chunk.asChunk(ByteBuffer.wrap(new byte[0]), false, referenceCounter2), sameInstance(Content.Chunk.EMPTY));
        assertThat(referenceCounter2.isRetained(), is(false));
        assertThat(referenceCounter2.release(), is(true));
    }
}
