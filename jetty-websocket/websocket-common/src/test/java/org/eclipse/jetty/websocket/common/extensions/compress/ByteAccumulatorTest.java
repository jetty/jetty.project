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

package org.eclipse.jetty.websocket.common.extensions.compress;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.MessageTooLargeException;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ByteAccumulatorTest
{
    @Test
    public void testCopyNormal()
    {
        ByteAccumulator accumulator = new ByteAccumulator(10_000);

        byte[] hello = "Hello".getBytes(UTF_8);
        byte[] space = " ".getBytes(UTF_8);
        byte[] world = "World".getBytes(UTF_8);

        accumulator.copyChunk(hello, 0, hello.length);
        accumulator.copyChunk(space, 0, space.length);
        accumulator.copyChunk(world, 0, world.length);

        assertThat("Length", accumulator.getLength(), is(hello.length + space.length + world.length));

        ByteBuffer out = ByteBuffer.allocate(200);
        accumulator.transferTo(out);
        String result = BufferUtil.toUTF8String(out);
        assertThat("ByteBuffer to UTF8", result, is("Hello World"));
    }

    @Test
    public void testTransferToNotEnoughSpace()
    {
        ByteAccumulator accumulator = new ByteAccumulator(10_000);

        byte[] hello = "Hello".getBytes(UTF_8);
        byte[] space = " ".getBytes(UTF_8);
        byte[] world = "World".getBytes(UTF_8);

        accumulator.copyChunk(hello, 0, hello.length);
        accumulator.copyChunk(space, 0, space.length);
        accumulator.copyChunk(world, 0, world.length);

        int length = hello.length + space.length + world.length;
        assertThat("Length", accumulator.getLength(), is(length));

        ByteBuffer out = ByteBuffer.allocate(length - 2); // intentionally too small ByteBuffer
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> accumulator.transferTo(out));
        assertThat(e.getMessage(), containsString("Not enough space in ByteBuffer"));
    }

    @Test
    public void testCopyChunkNotEnoughSpace()
    {

        byte[] hello = "Hello".getBytes(UTF_8);
        byte[] space = " ".getBytes(UTF_8);
        byte[] world = "World".getBytes(UTF_8);

        int length = hello.length + space.length + world.length;
        ByteAccumulator accumulator = new ByteAccumulator(length - 2); // intentionally too small of a max

        accumulator.copyChunk(hello, 0, hello.length);
        accumulator.copyChunk(space, 0, space.length);

        MessageTooLargeException e = assertThrows(MessageTooLargeException.class, () -> accumulator.copyChunk(world, 0, world.length));
        assertThat(e.getMessage(), containsString("too large for configured max"));
    }
}
