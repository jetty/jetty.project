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

package org.eclipse.jetty.quic.util;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VarLenIntTest
{
    @ParameterizedTest
    @ValueSource(longs = {37L, 15293L, 494878333L, 151288809941952652L})
    public void testEncodeDecodeByteBuffer(long value)
    {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        VarLenInt.encode(buffer, value);
        buffer.flip();

        long result = VarLenInt.decodeLong(buffer);
        assertEquals(value, result);
    }

    @ParameterizedTest
    @ValueSource(longs = {37L, 15293L, 494878333L, 151288809941952652L})
    public void testEncodeDecodeRetainableByteBuffer(long value)
    {
        RetainableByteBuffer.Mutable buffer = new RetainableByteBuffer.DynamicCapacity(null, false, -1, 0, 0);
        VarLenInt.encode(buffer, value);
        ByteBuffer byteBuffer = buffer.getByteBuffer();

        long result = VarLenInt.decodeLong(byteBuffer);
        assertEquals(value, result);
    }

    @ParameterizedTest
    @ValueSource(longs = {37L, 15293L, 494878333L, 151288809941952652L})
    public void testEncodeTryDecode(long value)
    {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        VarLenInt.encode(buffer, value);
        buffer.flip();

        AtomicLong result = new AtomicLong();
        boolean parsed = new VarLenInt().tryDecode(buffer, result::set);
        assertTrue(parsed);
        assertEquals(value, result.get());
    }
}
