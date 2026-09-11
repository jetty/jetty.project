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

import org.eclipse.jetty.util.Retainable;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ContentTest
{
    @Test
    public void testFromEmptyByteBufferWithoutReleaser()
    {
        assertThat(Content.Chunk.from(ByteBuffer.wrap(new byte[0]), true), sameInstance(Content.Chunk.EOF));
        assertThat(Content.Chunk.from(ByteBuffer.wrap(new byte[0]), false), sameInstance(Content.Chunk.EMPTY));
    }

    @Test
    public void testFromEmptyByteBufferWithRetainableReleaser()
    {
        Retainable.ReferenceCounter rc1 = new Retainable.ReferenceCounter();
        rc1.retain();
        assertThat(rc1.isRetained(), is(true));
        assertEquals(2, rc1.getCount());

        try (RetainableByteBuffer buffer = RetainableByteBuffer.wrap(ByteBuffer.allocate(0), rc1))
        {
            assertEquals(3, rc1.getCount());

            try (Content.Chunk chunk = Content.Chunk.from(buffer, true))
            {
                assertThat(chunk, sameInstance(Content.Chunk.EOF));
                assertEquals(3, rc1.getCount());
            }
            assertEquals(3, rc1.getCount());
        }
        assertEquals(2, rc1.getCount());

        // Pairs the initial retain().
        assertThat(rc1.release(), is(false));

        assertThat(rc1.isRetained(), is(false));
        assertThat(rc1.release(), is(true));

        Retainable.ReferenceCounter rc2 = new Retainable.ReferenceCounter();
        rc2.retain();
        assertThat(rc2.isRetained(), is(true));
        assertEquals(2, rc2.getCount());

        try (RetainableByteBuffer buffer = RetainableByteBuffer.wrap(ByteBuffer.allocate(0), rc2))
        {
            assertEquals(3, rc2.getCount());

            try (Content.Chunk chunk = Content.Chunk.from(buffer, false))
            {
                assertThat(chunk, sameInstance(Content.Chunk.EMPTY));
                assertEquals(3, rc2.getCount());
            }
            assertEquals(3, rc2.getCount());
        }
        assertEquals(2, rc2.getCount());

        // Pairs the initial retain().
        assertThat(rc2.release(), is(false));

        assertThat(rc2.isRetained(), is(false));
        assertThat(rc2.release(), is(true));
    }
}
