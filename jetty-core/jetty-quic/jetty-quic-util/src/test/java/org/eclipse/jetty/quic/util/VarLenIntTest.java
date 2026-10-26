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

import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VarLenIntTest
{
    @ParameterizedTest
    @ValueSource(longs = {37L, 15293L, 494878333L, 151288809941952652L})
    public void testEncodeDecode(long value)
    {
        RetainableByteBuffer.Mutable buffer = RetainableByteBuffer.Mutable.allocate(8, false);
        VarLenInt.encode(buffer, value);

        long result = VarLenInt.decodeLong(buffer);
        assertEquals(value, result);
    }

    @ParameterizedTest
    @ValueSource(longs = {37L, 15293L, 494878333L, 151288809941952652L})
    public void testEncodeTryDecode(long value)
    {
        RetainableByteBuffer.Mutable buffer = RetainableByteBuffer.Mutable.allocate(8, false);
        VarLenInt.encode(buffer, value);

        AtomicLong result = new AtomicLong();
        boolean parsed = new VarLenInt().tryDecode(buffer, result::set);
        assertTrue(parsed);
        assertEquals(value, result.get());
    }
}
