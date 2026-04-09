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

import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class WritableBufferPoolTest
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
        WritableBuffer wb = writableBufferPool.acquire(10, false);
        assertEquals(0, wb.position());
        assertThat(wb.remaining(), greaterThanOrEqualTo(10L));

        wb.putInt(1);
        wb.putInt(2);
        assertEquals(8, wb.position());
        assertThat(wb.remaining(), greaterThanOrEqualTo(2L));

        ReadableBuffer rb = wb.toReadable();
        assertEquals(0, rb.position());
        assertEquals(8, rb.remaining());
        assertEquals(1, rb.getInt());
        assertEquals(2, rb.getInt());
    }
}
