//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jetty.http3.internal.VarLenInt;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class VarLenIntTest
{
    @ParameterizedTest
    @ValueSource(longs = {37L, 15293L, 494878333L, 151288809941952652L})
    public void testGenerateParse(long value)
    {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        VarLenInt.encode(buffer, value);
        buffer.flip();

        AtomicLong result = new AtomicLong();
        boolean parsed = new VarLenInt().decode(buffer, result::set);
        assertTrue(parsed);
        assertEquals(value, result.get());
    }
}
