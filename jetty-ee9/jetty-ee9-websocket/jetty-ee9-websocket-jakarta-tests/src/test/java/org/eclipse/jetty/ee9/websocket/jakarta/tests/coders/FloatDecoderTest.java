//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.websocket.jakarta.tests.coders;

import jakarta.websocket.DecodeException;
import org.eclipse.jetty.ee9.websocket.jakarta.common.decoders.FloatDecoder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for class {@link FloatDecoder}.
 *
 * @see FloatDecoder
 */
public class FloatDecoderTest
{
    @Test
    public void testWillDecodeReturningTrue()
    {
        assertTrue(new FloatDecoder().willDecode("21"));
    }

    @Test
    public void testWillDecodeReturningFalse()
    {
        assertFalse(new FloatDecoder().willDecode("NaN"));
    }

    @Test
    public void testWillDecodeWithNull()
    {
        assertFalse(FloatDecoder.INSTANCE.willDecode(null));
    }

    @Test
    public void testDecodeThrowsDecodeException()
    {
        assertThrows(DecodeException.class, () -> new FloatDecoder().decode("NaN"));
    }

    @Test
    public void testDecode() throws DecodeException
    {
        assertEquals(4.1F, new FloatDecoder().decode("4.1"), 0.01F);
    }
}
