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

package org.eclipse.jetty.ee10.servlet;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EofException;
import org.eclipse.jetty.util.thread.AutoLock;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.fail;

public class BlockingContentProducerTest extends AbstractContentProducerTest
{
    @Test
    public void testSimple()
    {
        List<Content.Chunk> chunks = List.of(
            Content.Chunk.from(ByteBuffer.wrap("1 hello 1".getBytes(US_ASCII)), false),
            Content.Chunk.from(ByteBuffer.wrap("2 howdy 2".getBytes(US_ASCII)), false),
            Content.Chunk.from(ByteBuffer.wrap("3 hey ya 3".getBytes(US_ASCII)), true)
        );
        int totalContentBytesCount = countRemaining(chunks);
        String originalContentString = asString(chunks);

        ArrayDelayedServletChannel servletChannel = new ArrayDelayedServletChannel(chunks);
        ContentProducer contentProducer = servletChannel.getBlockingContentProducer();

        Throwable error = readAndAssertContent(contentProducer, servletChannel.getLock(),
            totalContentBytesCount, originalContentString,
            chunks.size(), c -> fail(c.getFailure()));
        assertThat(error, nullValue());
    }

    @Test
    public void testSimpleWithEof()
    {
        List<Content.Chunk> chunks = List.of(
            Content.Chunk.from(ByteBuffer.wrap("1 hello 1".getBytes(US_ASCII)), false),
            Content.Chunk.from(ByteBuffer.wrap("2 howdy 2".getBytes(US_ASCII)), false),
            Content.Chunk.from(ByteBuffer.wrap("3 hey ya 3".getBytes(US_ASCII)), false),
            Content.Chunk.EOF
        );
        int totalContentBytesCount = countRemaining(chunks);
        String originalContentString = asString(chunks);

        ArrayDelayedServletChannel servletChannel = new ArrayDelayedServletChannel(chunks);
        ContentProducer contentProducer = servletChannel.getBlockingContentProducer();

        Throwable error = readAndAssertContent(contentProducer, servletChannel.getLock(),
            totalContentBytesCount, originalContentString,
            chunks.size(), c -> fail(c.getFailure()));
        assertThat(error, nullValue());
    }

    @Test
    public void testWithLastError()
    {
        Throwable expectedError = new EofException("Early EOF");
        List<Content.Chunk> chunks = List.of(
            Content.Chunk.from(ByteBuffer.wrap("1 hello 1".getBytes(US_ASCII)), false),
            Content.Chunk.from(ByteBuffer.wrap("2 howdy 2".getBytes(US_ASCII)), false),
            Content.Chunk.from(ByteBuffer.wrap("3 hey ya 3".getBytes(US_ASCII)), false),
            Content.Chunk.from(expectedError, true)
        );
        int totalContentBytesCount = countRemaining(chunks);
        String originalContentString = asString(chunks);

        ArrayDelayedServletChannel servletChannel = new ArrayDelayedServletChannel(chunks);
        ContentProducer contentProducer = servletChannel.getBlockingContentProducer();

        Throwable error = readAndAssertContent(contentProducer, servletChannel.getLock(),
            totalContentBytesCount, originalContentString,
            chunks.size(), c -> fail(c.getFailure()));
        assertThat(error, is(expectedError));
    }

    @Test
    public void testWithTransientErrors()
    {
        List<Content.Chunk> chunks = List.of(
            Content.Chunk.from(ByteBuffer.wrap("1 hello 1".getBytes(US_ASCII)), false),
            Content.Chunk.from(new TimeoutException("timeout 1"), false),
            Content.Chunk.from(ByteBuffer.wrap("2 howdy 2".getBytes(US_ASCII)), false),
            Content.Chunk.from(new TimeoutException("timeout 2"), false),
            Content.Chunk.from(ByteBuffer.wrap("3 hey ya 3".getBytes(US_ASCII)), false),
            Content.Chunk.from(new TimeoutException("timeout 3"), false),
            Content.Chunk.EOF
        );
        int totalContentBytesCount = countRemaining(chunks);
        String originalContentString = asString(chunks);

        ArrayDelayedServletChannel servletChannel = new ArrayDelayedServletChannel(chunks);
        ContentProducer contentProducer = servletChannel.getBlockingContentProducer();

        Throwable error = readAndAssertContent(contentProducer, servletChannel.getLock(),
            totalContentBytesCount, originalContentString,
            chunks.size(), new Consumer<>()
            {
                int counter;

                @Override
                public void accept(Content.Chunk chunk)
                {
                    assertThat(chunk.isLast(), is(false));
                    assertThat(Content.Chunk.isFailure(chunk, true), is(false));
                    assertThat(Content.Chunk.isFailure(chunk, false), is(true));

                    Throwable x = chunk.getFailure();
                    assertThat(x, instanceOf(TimeoutException.class));
                    assertThat(x.getMessage(), equalTo("timeout " + ++counter));
                    assertThat(counter, lessThanOrEqualTo(3));

                    try (AutoLock ignore = servletChannel.getLock().lock())
                    {
                        assertThat(contentProducer.isError(), is(false));
                    }
                }
            });
        assertThat(error, nullValue());
    }

    private Throwable readAndAssertContent(ContentProducer contentProducer, AutoLock lock, int totalContentBytesCount, String originalContentString, int totalContentCount, Consumer<Content.Chunk> transientErrorConsumer)
    {
        int readBytes = 0;
        String consumedString = "";
        int nextContentCount = 0;
        Throwable failure;

        while (true)
        {
            Content.Chunk content;
            try (AutoLock ignore = lock.lock())
            {
                content = contentProducer.nextChunk();
            }
            nextContentCount++;
            assertThat(content, notNullValue());

            if (Content.Chunk.isFailure(content, false))
                transientErrorConsumer.accept(content);

            byte[] b = new byte[content.remaining()];
            readBytes += b.length;
            content.getByteBuffer().get(b);
            consumedString += new String(b, US_ASCII);
            content.skip(content.remaining());

            if (content.isLast())
            {
                failure = content.getFailure();
                break;
            }
        }

        assertThat(nextContentCount, is(totalContentCount));
        assertThat(readBytes, is(totalContentBytesCount));
        assertThat(consumedString, is(originalContentString));
        return failure;
    }
}
