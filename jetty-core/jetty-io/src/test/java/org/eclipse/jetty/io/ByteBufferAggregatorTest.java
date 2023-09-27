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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ByteBufferAggregatorTest
{
    private ArrayByteBufferPool.Tracking bufferPool;

    @BeforeEach
    public void before()
    {
        bufferPool = new ArrayByteBufferPool.Tracking();
    }

    @AfterEach
    public void tearDown()
    {
        assertThat("Leaks: " + bufferPool.dumpLeaks(), bufferPool.getLeaks().size(), is(0));
    }

    @Test
    public void testConstructor()
    {
        assertThrows(IllegalArgumentException.class, () -> new ByteBufferAggregator(bufferPool, true, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ByteBufferAggregator(bufferPool, true, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ByteBufferAggregator(bufferPool, true, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ByteBufferAggregator(bufferPool, true, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new ByteBufferAggregator(bufferPool, true, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ByteBufferAggregator(bufferPool, true, 2, 1));
    }

    @Test
    public void testFullInSingleShot()
    {
        ByteBufferAggregator aggregator = new ByteBufferAggregator(bufferPool, true, 1, 16);

        ByteBuffer byteBuffer1 = ByteBuffer.wrap(new byte[16]);
        assertThat(aggregator.aggregate(byteBuffer1), is(true));
        assertThat(byteBuffer1.remaining(), is(0));

        ByteBuffer byteBuffer2 = ByteBuffer.wrap(new byte[16]);
        assertThat(aggregator.aggregate(byteBuffer2), is(true));
        assertThat(byteBuffer2.remaining(), is(16));

        RetainableByteBuffer retainableByteBuffer = aggregator.takeRetainableByteBuffer();
        assertThat(retainableByteBuffer.getByteBuffer().remaining(), is(16));
        assertThat(retainableByteBuffer.release(), is(true));
    }

    @Test
    public void testFullInMultipleShots()
    {
        ByteBufferAggregator aggregator = new ByteBufferAggregator(bufferPool, true, 1, 16);

        ByteBuffer byteBuffer1 = ByteBuffer.wrap(new byte[15]);
        assertThat(aggregator.aggregate(byteBuffer1), is(false));
        assertThat(byteBuffer1.remaining(), is(0));

        ByteBuffer byteBuffer2 = ByteBuffer.wrap(new byte[16]);
        assertThat(aggregator.aggregate(byteBuffer2), is(true));
        assertThat(byteBuffer2.remaining(), is(15));

        RetainableByteBuffer retainableByteBuffer = aggregator.takeRetainableByteBuffer();
        assertThat(retainableByteBuffer.getByteBuffer().remaining(), is(16));
        assertThat(retainableByteBuffer.release(), is(true));
    }
}
