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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.coders;

import jakarta.websocket.DecodeException;
import org.eclipse.jetty.ee10.websocket.jakarta.common.decoders.IntegerDecoder;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class IntegerDecoderTest
{
    @Test
    public void testDecode() throws DecodeException
    {
        IntegerDecoder decoder = new IntegerDecoder();
        Integer val = decoder.decode("123");
        assertThat("Decoded value", val, is(123));
    }

    @Test
    public void testWillDecodeWithNull()
    {
        assertFalse(new IntegerDecoder().willDecode(null));
    }

    @Test
    public void testWillDecodeWithNonEmptyString()
    {
        assertFalse(new IntegerDecoder().willDecode("a"));
    }

    @Test
    public void testDecodeThrowsDecodeException()
    {
        assertThrows(DecodeException.class, () -> IntegerDecoder.INSTANCE.decode(""));
    }
}
