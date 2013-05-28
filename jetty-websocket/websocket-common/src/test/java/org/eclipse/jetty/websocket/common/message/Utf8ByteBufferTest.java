//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.*;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.junit.Assert;
import org.junit.Test;

public class Utf8ByteBufferTest
{
    private static String asString(ByteBuffer buffer)
    {
        return BufferUtil.toUTF8String(buffer);
    }

    private static byte[] asUTF(String str)
    {
        return str.getBytes(StringUtil.__UTF8_CHARSET);
    }

    @Test
    public void testAppendGetAppendGet()
    {
        ByteBuffer buf = ByteBuffer.allocate(64);
        Utf8ByteBuffer utf = Utf8ByteBuffer.wrap(buf);

        byte hellobytes[] = asUTF("Hello ");
        byte worldbytes[] = asUTF("World!");

        utf.append(hellobytes, 0, hellobytes.length);
        ByteBuffer hellobuf = utf.getBuffer();
        utf.append(worldbytes, 0, worldbytes.length);
        ByteBuffer worldbuf = utf.getBuffer();

        Assert.assertThat("Hello buffer",asString(hellobuf),is("Hello "));
        Assert.assertThat("World buffer",asString(worldbuf),is("Hello World!"));
    }

    @Test
    public void testAppendGetClearAppendGet()
    {
        ByteBuffer buf = ByteBuffer.allocate(64);
        Utf8ByteBuffer utf = Utf8ByteBuffer.wrap(buf);

        byte hellobytes[] = asUTF("Hello World");

        utf.append(hellobytes,0,hellobytes.length);
        ByteBuffer hellobuf = utf.getBuffer();

        Assert.assertThat("Hello buffer",asString(hellobuf),is("Hello World"));

        utf.clear();
        byte whatnowbytes[] = asUTF("What Now?");
        utf.append(whatnowbytes,0,whatnowbytes.length);
        ByteBuffer whatnowbuf = utf.getBuffer();
        Assert.assertThat("What buffer",asString(whatnowbuf),is("What Now?"));
    }

    @Test
    public void testSimpleGetBuffer()
    {
        ByteBuffer buf = ByteBuffer.allocate(64);
        Utf8ByteBuffer utf = Utf8ByteBuffer.wrap(buf);

        byte bb[] = asUTF("Hello World");
        utf.append(bb,0,bb.length);

        ByteBuffer actual = utf.getBuffer();
        Assert.assertThat("Buffer length",actual.remaining(),is(bb.length));

        Assert.assertThat("Message",asString(actual),is("Hello World"));
    }
}
