//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.jsr356.decoders;

import javax.websocket.DecodeException;

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
