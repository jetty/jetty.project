//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.message;

import static org.hamcrest.Matchers.is;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.BufferUtil;
import org.junit.Assert;
import org.junit.Test;

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

        byte hellobytes[] = asUTF("Hello ");
        byte worldbytes[] = asUTF("World!");

        utf.append(hellobytes, 0, hellobytes.length);
        ByteBuffer hellobuf = utf.getByteBuffer();
        utf.append(worldbytes, 0, worldbytes.length);
        ByteBuffer worldbuf = utf.getByteBuffer();

        Assert.assertThat("Hello buffer",asString(hellobuf),is("Hello "));
        Assert.assertThat("World buffer",asString(worldbuf),is("Hello World!"));
    }

    @Test
    public void testAppendGetClearAppendGet()
    {
        int bufsize = 128;
        ByteBuffer buf = ByteBuffer.allocate(bufsize);
        Utf8CharBuffer utf = Utf8CharBuffer.wrap(buf);

        int expectedSize = bufsize / 2;
        Assert.assertThat("Remaining (initial)",utf.remaining(),is(expectedSize));

        byte hellobytes[] = asUTF("Hello World");

        utf.append(hellobytes,0,hellobytes.length);
        ByteBuffer hellobuf = utf.getByteBuffer();

        Assert.assertThat("Remaining (after append)",utf.remaining(),is(expectedSize - hellobytes.length));
        Assert.assertThat("Hello buffer",asString(hellobuf),is("Hello World"));

        utf.clear();

        Assert.assertThat("Remaining (after clear)",utf.remaining(),is(expectedSize));

        byte whatnowbytes[] = asUTF("What Now?");
        utf.append(whatnowbytes,0,whatnowbytes.length);
        ByteBuffer whatnowbuf = utf.getByteBuffer();

        Assert.assertThat("Remaining (after 2nd append)",utf.remaining(),is(expectedSize - whatnowbytes.length));
        Assert.assertThat("What buffer",asString(whatnowbuf),is("What Now?"));
    }

    @Test
    public void testAppendUnicodeGetBuffer()
    {
        ByteBuffer buf = ByteBuffer.allocate(64);
        Utf8CharBuffer utf = Utf8CharBuffer.wrap(buf);

        byte bb[] = asUTF("Hello A\u00ea\u00f1\u00fcC");
        utf.append(bb,0,bb.length);

        ByteBuffer actual = utf.getByteBuffer();
        Assert.assertThat("Buffer length should be retained",actual.remaining(),is(bb.length));
        Assert.assertThat("Message",asString(actual),is("Hello A\u00ea\u00f1\u00fcC"));
    }

    @Test
    public void testSimpleGetBuffer()
    {
        int bufsize = 64;
        ByteBuffer buf = ByteBuffer.allocate(bufsize);
        Utf8CharBuffer utf = Utf8CharBuffer.wrap(buf);

        int expectedSize = bufsize / 2;
        Assert.assertThat("Remaining (initial)",utf.remaining(),is(expectedSize));

        byte bb[] = asUTF("Hello World");
        utf.append(bb,0,bb.length);

        expectedSize -= bb.length;
        Assert.assertThat("Remaining (after append)",utf.remaining(),is(expectedSize));

        ByteBuffer actual = utf.getByteBuffer();
        Assert.assertThat("Buffer length",actual.remaining(),is(bb.length));

        Assert.assertThat("Message",asString(actual),is("Hello World"));
    }
}
