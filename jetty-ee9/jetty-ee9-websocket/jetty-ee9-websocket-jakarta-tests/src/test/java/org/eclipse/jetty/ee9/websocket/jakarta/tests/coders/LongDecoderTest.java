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

package org.eclipse.jetty.websocket.jakarta.tests.coders;

import jakarta.websocket.DecodeException;
import org.eclipse.jetty.websocket.jakarta.common.decoders.LongDecoder;
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
