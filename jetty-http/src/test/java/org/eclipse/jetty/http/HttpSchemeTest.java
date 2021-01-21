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
