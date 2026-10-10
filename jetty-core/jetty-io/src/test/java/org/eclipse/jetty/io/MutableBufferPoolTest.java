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

import java.util.List;

import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class MutableBufferPoolTest
{
    static List<WritableBufferPool> sources()
    {
        return List.of(
            WritableBufferPool.NON_POOLING,
            WritableBufferPool.wrap(new ArrayByteBufferPool())
        );
    }

    @ParameterizedTest
    @MethodSource("sources")
    public void testSimple(WritableBufferPool writableBufferPool)
    {
        RetainableByteBuffer.Mutable b = writableBufferPool.acquire(10, false);
        assertEquals(0, b.writePosition());
        assertThat(b.space(), greaterThanOrEqualTo(10L));

        b.putInt(1);
        b.putInt(2);
        assertEquals(8, b.writePosition());
        assertThat(b.space(), greaterThanOrEqualTo(2L));

        assertEquals(0, b.readPosition());
        assertEquals(8, b.remaining());
        assertEquals(1, b.getInt());
        assertEquals(2, b.getInt());
    }
}
