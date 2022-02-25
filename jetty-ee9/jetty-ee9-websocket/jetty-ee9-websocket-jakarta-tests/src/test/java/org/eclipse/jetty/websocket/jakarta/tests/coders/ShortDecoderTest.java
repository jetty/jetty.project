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
import org.eclipse.jetty.websocket.jakarta.common.decoders.ShortDecoder;
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
    public void testDecodeThrowsDecodeException()
    {
        assertThrows(DecodeException.class, () -> ShortDecoder.INSTANCE.decode("$Yta3*m*%"));
    }
}
