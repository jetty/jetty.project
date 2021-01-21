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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for class {@link LongDecoder}.
 *
 * @see LongDecoder
 */
public class LongDecoderTest
{

    @Test
    public void testCreatesLongDecoder()
    {
        assertFalse(new LongDecoder().willDecode(null));
    }

    @Test
    public void testWillDecodeWithNonEmptyString()
    {
        assertFalse(LongDecoder.INSTANCE.willDecode("Unable to parse Long"));
    }

    @Test
    public void testDecodeThrowsDecodeException()
    {
        assertThrows(DecodeException.class, () -> LongDecoder.INSTANCE.decode("Unable to parse Long"));
    }
}
