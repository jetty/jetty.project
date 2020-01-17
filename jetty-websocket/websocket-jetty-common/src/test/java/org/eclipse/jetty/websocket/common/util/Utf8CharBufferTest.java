//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.common.util;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class Utf8CharBufferTest
{
    private static String asString(ByteBuffer buffer)
    {
        return BufferUtil.toUTF8String(buffer);
    }

    private static byte[] asUTF(String str)
    {
        return str.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void testAppendGetAppendGet()
    {
        ByteBuffer buf = ByteBuffer.allocate(128);
        Utf8CharBuffer utf = Utf8CharBuffer.wrap(buf);

        byte[] hellobytes = asUTF("Hello ");
        byte[] worldbytes = asUTF("World!");

        utf.append(hellobytes, 0, hellobytes.length);
        ByteBuffer hellobuf = utf.getByteBuffer();
        utf.append(worldbytes, 0, worldbytes.length);
        ByteBuffer worldbuf = utf.getByteBuffer();

        assertThat("Hello buffer", asString(hellobuf), is("Hello "));
        assertThat("World buffer", asString(worldbuf), is("Hello World!"));
    }

    @Test
    public void testAppendGetClearAppendGet()
    {
        int bufsize = 128;
        ByteBuffer buf = ByteBuffer.allocate(bufsize);
        Utf8CharBuffer utf = Utf8CharBuffer.wrap(buf);

        int expectedSize = bufsize / 2;
        assertThat("Remaining (initial)", utf.remaining(), is(expectedSize));

        byte[] hellobytes = asUTF("Hello World");

        utf.append(hellobytes, 0, hellobytes.length);
        ByteBuffer hellobuf = utf.getByteBuffer();

        assertThat("Remaining (after append)", utf.remaining(), is(expectedSize - hellobytes.length));
        assertThat("Hello buffer", asString(hellobuf), is("Hello World"));

        utf.clear();

        assertThat("Remaining (after clear)", utf.remaining(), is(expectedSize));

        byte[] whatnowbytes = asUTF("What Now?");
        utf.append(whatnowbytes, 0, whatnowbytes.length);
        ByteBuffer whatnowbuf = utf.getByteBuffer();

        assertThat("Remaining (after 2nd append)", utf.remaining(), is(expectedSize - whatnowbytes.length));
        assertThat("What buffer", asString(whatnowbuf), is("What Now?"));
    }

    @Test
    public void testAppendUnicodeGetBuffer()
    {
        ByteBuffer buf = ByteBuffer.allocate(64);
        Utf8CharBuffer utf = Utf8CharBuffer.wrap(buf);

        // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        byte[] bb = asUTF("Hello A\u00ea\u00f1\u00fcC");
        utf.append(bb, 0, bb.length);

        ByteBuffer actual = utf.getByteBuffer();
        assertThat("Buffer length should be retained", actual.remaining(), is(bb.length));
        assertThat("Message", asString(actual), is("Hello A\u00ea\u00f1\u00fcC"));
    }

    @Test
    public void testSimpleGetBuffer()
    {
        int bufsize = 64;
        ByteBuffer buf = ByteBuffer.allocate(bufsize);
        Utf8CharBuffer utf = Utf8CharBuffer.wrap(buf);

        int expectedSize = bufsize / 2;
        assertThat("Remaining (initial)", utf.remaining(), is(expectedSize));

        byte[] bb = asUTF("Hello World");
        utf.append(bb, 0, bb.length);

        expectedSize -= bb.length;
        assertThat("Remaining (after append)", utf.remaining(), is(expectedSize));

        ByteBuffer actual = utf.getByteBuffer();
        assertThat("Buffer length", actual.remaining(), is(bb.length));

        assertThat("Message", asString(actual), is("Hello World"));
    }
}
