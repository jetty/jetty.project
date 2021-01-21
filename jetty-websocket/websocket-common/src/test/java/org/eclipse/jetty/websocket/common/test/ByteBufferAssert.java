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

package org.eclipse.jetty.websocket.common.test;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * @deprecated use {@link org.eclipse.jetty.toolchain.test.ByteBufferAssert} instead
 */
public class ByteBufferAssert
{
    public static void assertEquals(String message, byte[] expected, byte[] actual)
    {
        assertThat(message + " byte[].length", actual.length, is(expected.length));
        int len = expected.length;
        for (int i = 0; i < len; i++)
        {
            assertThat(message + " byte[" + i + "]", actual[i], is(expected[i]));
        }
    }

    public static void assertEquals(ByteBuffer expectedBuffer, ByteBuffer actualBuffer, String message)
    {
        assertEquals(message, expectedBuffer, actualBuffer);
    }

    public static void assertEquals(String message, ByteBuffer expectedBuffer, ByteBuffer actualBuffer)
    {
        if (expectedBuffer == null)
        {
            assertThat(message, actualBuffer, nullValue());
        }
        else
        {
            byte[] expectedBytes = BufferUtil.toArray(expectedBuffer);
            byte[] actualBytes = BufferUtil.toArray(actualBuffer);
            assertEquals(message, expectedBytes, actualBytes);
        }
    }

    public static void assertEquals(String message, String expectedString, ByteBuffer actualBuffer)
    {
        String actualString = BufferUtil.toString(actualBuffer);
        assertThat(message, actualString, is(expectedString));
    }

    public static void assertSize(String message, int expectedSize, ByteBuffer buffer)
    {
        if ((expectedSize == 0) && (buffer == null))
        {
            return;
        }
        assertThat(message + " buffer.remaining", buffer.remaining(), is(expectedSize));
    }
}
