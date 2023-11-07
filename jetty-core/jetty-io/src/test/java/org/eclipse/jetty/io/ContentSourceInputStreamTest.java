//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.content.ContentSourceInputStream;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.fail;

public class ContentSourceInputStreamTest
{
    @Test
    public void testTransientErrorsAreRethrownOnRead() throws Exception
    {
        TimeoutException originalFailure1 = new TimeoutException("timeout 1");
        TimeoutException originalFailure2 = new TimeoutException("timeout 2");
        TestSource originalSource = new TestSource(
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{'1'}), false),
            null,
            Content.Chunk.from(originalFailure1, false),
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{'2'}), false),
            null,
            Content.Chunk.from(originalFailure2, false),
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{'3'}), true)
        );

        ContentSourceInputStream contentSourceInputStream = new ContentSourceInputStream(originalSource);

        byte[] buf = new byte[16];

        int read = contentSourceInputStream.read(buf);
        assertThat(read, is(1));
        assertThat(buf[0], is((byte)'1'));
        try
        {
            contentSourceInputStream.read();
            fail();
        }
        catch (IOException e)
        {
            assertThat(e.getCause(), sameInstance(originalFailure1));
        }
        read = contentSourceInputStream.read(buf);
        assertThat(read, is(1));
        assertThat(buf[0], is((byte)'2'));
        try
        {
            contentSourceInputStream.read();
            fail();
        }
        catch (IOException e)
        {
            assertThat(e.getCause(), sameInstance(originalFailure2));
        }
        read = contentSourceInputStream.read(buf);
        assertThat(read, is(1));
        assertThat(buf[0], is((byte)'3'));

        read = contentSourceInputStream.read(buf);
        assertThat(read, is(-1));

        Content.Chunk chunk = originalSource.read();
        assertThat(chunk.isLast(), is(true));
        assertThat(chunk.hasRemaining(), is(false));
        assertThat(Content.Chunk.isFailure(chunk), is(false));

        contentSourceInputStream.close();
    }

    @Test
    public void testNextTransientErrorIsRethrownOnClose() throws Exception
    {
        TimeoutException originalFailure = new TimeoutException("timeout");
        TestSource originalSource = new TestSource(
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{'1'}), false),
            Content.Chunk.from(originalFailure, false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{'2'}), true)
        );

        ContentSourceInputStream contentSourceInputStream = new ContentSourceInputStream(originalSource);

        byte[] buf = new byte[16];

        int read = contentSourceInputStream.read(buf);
        assertThat(read, is(1));
        assertThat(buf[0], is((byte)'1'));
        try
        {
            contentSourceInputStream.close();
            fail();
        }
        catch (IOException e)
        {
            assertThat(e.getCause(), sameInstance(originalFailure));
        }

        Content.Chunk chunk = originalSource.read();
        assertThat(chunk.isLast(), is(true));
        assertThat(chunk.getFailure(), sameInstance(originalFailure));
    }
}
