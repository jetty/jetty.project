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

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.content.ContentSourceCompletableFuture;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.fail;

public class ContentSourceCompletableFutureTest
{
    @Test
    public void testTransientErrorsBecomeTerminalErrors() throws Exception
    {
        TimeoutException originalFailure = new TimeoutException("timeout 1");
        TestSource originalSource = new TestSource(
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{'1'}), false),
            null,
            Content.Chunk.from(originalFailure, false),
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{'2'}), true)
        );

        ContentSourceCompletableFuture<String> contentSourceCompletableFuture = new ContentSourceCompletableFuture<>(originalSource)
        {
            final Utf8StringBuilder builder = new Utf8StringBuilder();

            @Override
            protected String parse(Content.Chunk chunk)
            {
                if (chunk.hasRemaining())
                    builder.append(chunk.getByteBuffer());
                if (!chunk.isLast())
                    return null;
                return builder.takeCompleteString(IllegalStateException::new);
            }
        };

        try
        {
            contentSourceCompletableFuture.parse();
            contentSourceCompletableFuture.get();
            fail();
        }
        catch (ExecutionException e)
        {
            assertThat(e.getCause(), sameInstance(originalFailure));
        }

        Content.Chunk chunk = originalSource.read();
        assertThat(chunk.isLast(), is(true));
        assertThat(chunk.getFailure(), sameInstance(originalFailure));
    }

    @Test
    public void testTransientErrorsAreIgnored() throws Exception
    {
        TestSource originalSource = new TestSource(
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{'1'}), false),
            null,
            Content.Chunk.from(new TimeoutException("timeout 1"), false),
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{'2'}), false),
            null,
            Content.Chunk.from(new TimeoutException("timeout 2"), false),
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{'3'}), true)
        );

        ContentSourceCompletableFuture<String> contentSourceCompletableFuture = new ContentSourceCompletableFuture<>(originalSource)
        {
            final Utf8StringBuilder builder = new Utf8StringBuilder();

            @Override
            protected String parse(Content.Chunk chunk)
            {
                if (chunk.hasRemaining())
                    builder.append(chunk.getByteBuffer());
                if (!chunk.isLast())
                    return null;
                return builder.takeCompleteString(IllegalStateException::new);
            }

            @Override
            protected boolean onTransientFailure(Throwable cause)
            {
                return true;
            }
        };

        contentSourceCompletableFuture.parse();
        assertThat(contentSourceCompletableFuture.get(), is("123"));

        Content.Chunk chunk = originalSource.read();
        assertThat(chunk.isLast(), is(true));
        assertThat(chunk.hasRemaining(), is(false));
    }
}
