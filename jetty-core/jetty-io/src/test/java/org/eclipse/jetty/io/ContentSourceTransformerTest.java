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

package org.eclipse.jetty.io;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.content.AsyncContent;
import org.eclipse.jetty.io.content.ContentSourceTransformer;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContentSourceTransformerTest
{
    @Test
    public void testNoChunks()
    {
        AsyncContent source = new AsyncContent();
        WordSplitLowCaseTransformer transformer = new WordSplitLowCaseTransformer(source);

        Content.Chunk chunk = transformer.read();
        assertNull(chunk);

        FutureCallback callback = new FutureCallback();
        transformer.demand(callback::succeeded);
        assertFalse(callback.isDone());

        source.close();

        assertTrue(callback.isDone());

        chunk = transformer.read();
        assertNotNull(chunk);
        assertTrue(chunk.isLast());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void testTwoChunksAndEOF(boolean last)
    {
        AsyncContent source = new AsyncContent();
        source.write(last, UTF_8.encode("ONE two"), Callback.NOOP);
        if (!last)
            source.close();
        WordSplitLowCaseTransformer transformer = new WordSplitLowCaseTransformer(source);

        Content.Chunk chunk = transformer.read();
        assertNotNull(chunk);
        assertEquals("one", UTF_8.decode(chunk.getByteBuffer()).toString());

        chunk = transformer.read();
        assertNotNull(chunk);
        assertEquals("two", UTF_8.decode(chunk.getByteBuffer()).toString());
        if (last)
            assertTrue(chunk.isLast());

        chunk = transformer.read();
        assertNotNull(chunk);
        assertTrue(chunk.isLast());
    }

    @Test
    public void testDemandFirstWithLoop()
    {
        AsyncContent source = new AsyncContent();
        source.write(true, UTF_8.encode("ONE two"), Callback.NOOP);
        WordSplitLowCaseTransformer transformer = new WordSplitLowCaseTransformer(source);

        AtomicBoolean processed = new AtomicBoolean();
        transformer.demand(() ->
        {
            processed.set(true);
            while (true)
            {
                Content.Chunk chunk = transformer.read();
                assertNotNull(chunk);
                if (chunk.isLast())
                    break;
            }
        });

        assertTrue(processed.get());
    }

    @Test
    public void testDemandFirstWithoutLoop()
    {
        AsyncContent source = new AsyncContent();
        source.write(true, UTF_8.encode("ONE NOOP two"), Callback.NOOP);
        WordSplitLowCaseTransformer transformer = new WordSplitLowCaseTransformer(source);

        AtomicBoolean reEnter = new AtomicBoolean();
        Queue<String> expected = new ArrayDeque<>(List.of("one", "two"));
        transformer.demand(new Runnable()
        {
            @Override
            public void run()
            {
                if (!reEnter.compareAndSet(false, true))
                    throw new IllegalStateException();

                Content.Chunk chunk = transformer.read();
                assertNotNull(chunk);
                assertEquals(expected.poll(), UTF_8.decode(chunk.getByteBuffer()).toString());

                if (!chunk.isLast())
                    transformer.demand(this);

                if (!reEnter.compareAndSet(true, false))
                    throw new IllegalStateException();
            }
        });

        assertThat(expected, empty());
    }

    @Test
    public void testDemandFirstWithoutLoopStallAfterTwoExpectedChunks()
    {
        AsyncContent source = new AsyncContent();
        source.write(false, UTF_8.encode("ONE NOOP two"), Callback.NOOP);
        WordSplitLowCaseTransformer transformer = new WordSplitLowCaseTransformer(source);

        AtomicBoolean reEnter = new AtomicBoolean();
        Queue<String> expected = new ArrayDeque<>(List.of("one", "two"));
        transformer.demand(new Runnable()
        {
            @Override
            public void run()
            {
                if (!reEnter.compareAndSet(false, true))
                    throw new IllegalStateException();

                Content.Chunk chunk = transformer.read();
                if (chunk != null)
                    assertEquals(expected.poll(), UTF_8.decode(chunk.getByteBuffer()).toString());

                if (chunk == null || !chunk.isLast())
                    transformer.demand(this);

                if (!reEnter.compareAndSet(true, false))
                    throw new IllegalStateException();
            }
        });

        assertThat(expected, empty());

        expected.offer("three");
        source.write(true, UTF_8.encode("three"), Callback.NOOP);
        assertThat(expected, empty());

        expected.offer("EOF");
        transformer.demand(() ->
        {
            Content.Chunk chunk = transformer.read();
            assertTrue(chunk.isLast());
            assertFalse(chunk.hasRemaining());
            expected.poll();
        });

        assertThat(expected, empty());
    }

    @Test
    public void testDemandFirstThenConsumeAllChunks()
    {
        AsyncContent source = new AsyncContent();
        source.write(true, UTF_8.encode("ONE NOOP two"), Callback.NOOP);
        WordSplitLowCaseTransformer transformer = new WordSplitLowCaseTransformer(source);

        AtomicInteger count = new AtomicInteger();
        AtomicBoolean reEnter = new AtomicBoolean();
        transformer.demand(new Runnable()
        {
            @Override
            public void run()
            {
                if (count.incrementAndGet() > 1)
                    return;

                if (!reEnter.compareAndSet(false, true))
                    throw new IllegalStateException();

                Content.Chunk chunk = transformer.read();
                assertNotNull(chunk);
                assertEquals("one", UTF_8.decode(chunk.getByteBuffer()).toString());

                // This demand will be fulfilled later after the last chunk has been read.
                transformer.demand(this);

                chunk = transformer.read();
                assertNotNull(chunk);
                assertEquals("two", UTF_8.decode(chunk.getByteBuffer()).toString());
                assertTrue(chunk.isLast());

                if (!reEnter.compareAndSet(true, false))
                    throw new IllegalStateException();
            }
        });

        // The pending demand will be fulfilled after reading
        // the last chunk, so the Runnable will be invoked twice.
        assertEquals(2, count.get());
    }

    @Test
    public void testTransformThrows()
    {
        AsyncContent source = new AsyncContent();
        source.write(false, UTF_8.encode("ONE"), Callback.NOOP);
        source.write(false, UTF_8.encode("THROW"), Callback.NOOP);
        source.write(true, UTF_8.encode("two"), Callback.NOOP);
        WordSplitLowCaseTransformer transformer = new WordSplitLowCaseTransformer(source);

        Content.Chunk chunk = transformer.read();
        assertNotNull(chunk);
        assertEquals("one", UTF_8.decode(chunk.getByteBuffer()).toString());

        chunk = transformer.read();
        assertInstanceOf(Content.Chunk.Error.class, chunk);

        // Trying to read again returns the error again.
        chunk = transformer.read();
        assertInstanceOf(Content.Chunk.Error.class, chunk);

        // Make sure that the source is failed.
        assertEquals(0, source.count());
    }

    @Test
    public void testTransformReturnsError()
    {
        AsyncContent source = new AsyncContent();
        source.write(false, UTF_8.encode("ONE"), Callback.NOOP);
        source.write(false, UTF_8.encode("ERROR"), Callback.NOOP);
        source.write(true, UTF_8.encode("two"), Callback.NOOP);
        WordSplitLowCaseTransformer transformer = new WordSplitLowCaseTransformer(source);

        Content.Chunk chunk = transformer.read();
        assertNotNull(chunk);
        assertEquals("one", UTF_8.decode(chunk.getByteBuffer()).toString());

        chunk = transformer.read();
        assertInstanceOf(Content.Chunk.Error.class, chunk);

        // Trying to read again returns the error again.
        chunk = transformer.read();
        assertInstanceOf(Content.Chunk.Error.class, chunk);
    }

    @Test
    public void testSourceReturnsError()
    {
        AsyncContent source = new AsyncContent();
        source.write(false, UTF_8.encode("ONE"), Callback.NOOP);
        WordSplitLowCaseTransformer transformer = new WordSplitLowCaseTransformer(source);

        Content.Chunk chunk = transformer.read();
        assertNotNull(chunk);
        assertEquals("one", UTF_8.decode(chunk.getByteBuffer()).toString());

        source.fail(new IOException());

        chunk = transformer.read();
        assertInstanceOf(Content.Chunk.Error.class, chunk);

        // Trying to read again returns the error again.
        chunk = transformer.read();
        assertInstanceOf(Content.Chunk.Error.class, chunk);
    }

    private static class WordSplitLowCaseTransformer extends ContentSourceTransformer
    {
        private final Queue<Content.Chunk> chunks = new ArrayDeque<>();

        private WordSplitLowCaseTransformer(Content.Source rawSource)
        {
            super(rawSource);
        }

        @Override
        protected Content.Chunk transform(Content.Chunk rawChunk)
        {
            if (rawChunk != null)
            {
                String rawString = UTF_8.decode(rawChunk.getByteBuffer()).toString();
                String[] strings = rawString.split("\\s");
                boolean last = false;
                for (int i = 0; i < strings.length; ++i)
                {
                    String string = strings[i];
                    string = string.trim();
                    if (string.isEmpty())
                        continue;
                    if ("NOOP".equalsIgnoreCase(string))
                        continue;
                    if ("THROW".equalsIgnoreCase(string))
                        throw new RuntimeException();
                    if ("ERROR".equalsIgnoreCase(string))
                        return Content.Chunk.from(new IOException());
                    string = string.toLowerCase(Locale.ENGLISH);
                    last = rawChunk.isLast() && i == strings.length - 1;
                    chunks.offer(Content.Chunk.from(UTF_8.encode(string), last));
                }
                if (rawChunk.isLast() && !last)
                    chunks.offer(Content.Chunk.EOF);
            }
            return chunks.poll();
        }
    }
}
