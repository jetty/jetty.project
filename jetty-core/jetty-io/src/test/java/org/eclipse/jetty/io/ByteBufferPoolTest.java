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
import java.nio.ByteOrder;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests of various {@link ByteBufferPool} implementations to ensure
 * that they satisfy the {@link ByteBufferPool} contracts.
 */
public class ByteBufferPoolTest
{
    public static Stream<ByteBufferPool> implementations()
    {
        return Stream.of(
            new ArrayByteBufferPool(),
            new ArrayByteBufferPool.Tracking()
        );
    }

    @ParameterizedTest
    @MethodSource("implementations")
    public void testResetOrder(ByteBufferPool pool)
    {
        RetainableByteBuffer.Mutable retainableByteBuffer = pool.acquire(2048, true);
        ByteBuffer buffer = retainableByteBuffer.getByteBuffer();
        Assertions.assertEquals(ByteOrder.BIG_ENDIAN, buffer.order());
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        Assertions.assertEquals(ByteOrder.LITTLE_ENDIAN, buffer.order());
        retainableByteBuffer.release();
        Assertions.assertEquals(ByteOrder.BIG_ENDIAN, buffer.order());
    }
}
