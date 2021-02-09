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
