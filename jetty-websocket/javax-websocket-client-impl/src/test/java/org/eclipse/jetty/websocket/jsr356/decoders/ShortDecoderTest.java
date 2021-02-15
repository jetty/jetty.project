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
 * Unit tests for class {@link ShortDecoder}.
 *
 * @see ShortDecoder
 */
public class ShortDecoderTest
{

    @Test
    public void testWillDecodeWithNull()
    {
        assertFalse(new ShortDecoder().willDecode(null));
    }

    @Test
    public void testWillDecodeWithNonEmptyString()
    {
        assertFalse(new ShortDecoder().willDecode(".iix/PN}f[&-<n$B9q"));
    }

    @Test
    public void testDecodeThrowsDecodeException() throws DecodeException
    {
        assertThrows(DecodeException.class, () -> ShortDecoder.INSTANCE.decode("$Yta3*m*%"));
    }
}
