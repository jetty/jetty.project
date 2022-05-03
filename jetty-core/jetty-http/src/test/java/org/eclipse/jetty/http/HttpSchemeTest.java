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

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for class {@link HttpScheme}.
 *
 * @see HttpScheme
 */
public class HttpSchemeTest
{

    @Test
    public void testIsReturningTrue()
    {
        HttpScheme httpScheme = HttpScheme.HTTPS;

        assertTrue(httpScheme.is("https"));
        assertEquals("https", httpScheme.asString());
        assertEquals("https", httpScheme.toString());
    }

    @Test
    public void testIsReturningFalse()
    {
        HttpScheme httpScheme = HttpScheme.HTTP;

        assertFalse(httpScheme.is(",CPL@@4'U4p"));
    }

    @Test
    public void testIsWithNull()
    {
        HttpScheme httpScheme = HttpScheme.HTTPS;

        assertFalse(httpScheme.is(null));
    }

    @Test
    public void testAsByteBuffer()
    {
        HttpScheme httpScheme = HttpScheme.WS;
        ByteBuffer byteBuffer = httpScheme.asByteBuffer();

        assertEquals("ws", httpScheme.asString());
        assertEquals("ws", httpScheme.toString());
        assertEquals(2, byteBuffer.capacity());
        assertEquals(2, byteBuffer.remaining());
        assertEquals(2, byteBuffer.limit());
        assertFalse(byteBuffer.hasArray());
        assertEquals(0, byteBuffer.position());
        assertTrue(byteBuffer.isReadOnly());
        assertFalse(byteBuffer.isDirect());
        assertTrue(byteBuffer.hasRemaining());
    }
}
