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
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.io.content.AsyncContent;
import org.eclipse.jetty.io.content.ContentSourceTransformer;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.FutureCallback;
import org.eclipse.jetty.util.thread.SerializedInvoker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        chunk.release();
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
        chunk.release();

        chunk = transformer.read();
        assertNotNull(chunk);
        assertEquals("two", UTF_8.decode(chunk.getByteBuffer()).toString());
        chunk.release();
        if (last)
            assertTrue(chunk.isLast());

        chunk = transformer.read();
        assertNotNull(chunk);
        chunk.release();
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
                chunk.release();
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
                chunk.release();

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
                {
                    assertEquals(expected.poll(), UTF_8.decode(chunk.getByteBuffer()).toString());
                    chunk.release();
                }

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
            chunk.release();
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
                chunk.release();

                // This demand will be fulfilled later after the last chunk has been read.
                transformer.demand(this);

                chunk = transformer.read();
                assertNotNull(chunk);
                assertEquals("two", UTF_8.decode(chunk.getByteBuffer()).toString());
                assertTrue(chunk.isLast());
                chunk.release();

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
        chunk.release();

        chunk = transformer.read();
        assertTrue(Content.Chunk.isFailure(chunk, true));

        // Trying to read again returns the error again.
        chunk = transformer.read();
        assertTrue(Content.Chunk.isFailure(chunk, true));

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
        chunk.release();

        chunk = transformer.read();
        assertTrue(Content.Chunk.isFailure(chunk, true));

        // Trying to read again returns the error again.
        chunk = transformer.read();
        assertTrue(Content.Chunk.isFailure(chunk, true));
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
        chunk.release();

        source.fail(new IOException());

        chunk = transformer.read();
        assertTrue(Content.Chunk.isFailure(chunk, true));

        // Trying to read again returns the error again.
        chunk = transformer.read();
        assertTrue(Content.Chunk.isFailure(chunk, true));
    }

    @Test
    public void testTransientFailuresFromOriginalSourceAreReturned()
    {
        TimeoutException originalFailure1 = new TimeoutException("timeout 1");
        TimeoutException originalFailure2 = new TimeoutException("timeout 2");
        TestSource originalSource = new TestSource(
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{'A'}), false),
            Content.Chunk.from(originalFailure1, false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{'B'}), false),
            Content.Chunk.from(originalFailure2, false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{'C'}), true)
        );

        WordSplitLowCaseTransformer transformer = new WordSplitLowCaseTransformer(originalSource);

        assertEquals('a', (char)transformer.read().getByteBuffer().get());
        Content.Chunk chunk = transformer.read();
        assertThat(chunk.getFailure(), sameInstance(originalFailure1));
        assertThat(chunk.isLast(), is(false));
        assertEquals('b', (char)transformer.read().getByteBuffer().get());
        chunk = transformer.read();
        assertThat(chunk.getFailure(), sameInstance(originalFailure2));
        assertThat(chunk.isLast(), is(false));
        assertEquals('c', (char)transformer.read().getByteBuffer().get());

        chunk = originalSource.read();
        assertThat(chunk.isLast(), is(true));
        assertThat(chunk.hasRemaining(), is(false));
        assertThat(Content.Chunk.isFailure(chunk), is(false));

        originalSource.close();
    }

    @Test
    public void testTransientFailuresFromTransformationAreReturned()
    {
        TimeoutException originalFailure1 = new TimeoutException("timeout 1");
        TimeoutException originalFailure2 = new TimeoutException("timeout 2");
        TestSource originalSource = new TestSource(
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{'A'}), false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{'B'}), false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{'C'}), false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{'D'}), false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{'E'}), true)
        );

        ContentSourceTransformer transformer = new ContentSourceTransformer(originalSource)
        {
            @Override
            protected Content.Chunk transform(Content.Chunk rawChunk)
            {
                String decoded = UTF_8.decode(rawChunk.getByteBuffer()).toString();
                return switch (decoded)
                {
                    case "B" -> Content.Chunk.from(originalFailure1, false);
                    case "D" -> Content.Chunk.from(originalFailure2, false);
                    default -> Content.Chunk.from(UTF_8.encode(decoded), rawChunk.isLast());
                };
            }
        };

        assertEquals('A', (char)transformer.read().getByteBuffer().get());
        Content.Chunk chunk = transformer.read();
        assertThat(chunk.getFailure(), sameInstance(originalFailure1));
        assertThat(chunk.isLast(), is(false));
        assertEquals('C', (char)transformer.read().getByteBuffer().get());
        chunk = transformer.read();
        assertThat(chunk.getFailure(), sameInstance(originalFailure2));
        assertThat(chunk.isLast(), is(false));
        assertEquals('E', (char)transformer.read().getByteBuffer().get());

        chunk = originalSource.read();
        assertThat(chunk.isLast(), is(true));
        assertThat(chunk.hasRemaining(), is(false));
        assertThat(Content.Chunk.isFailure(chunk), is(false));

        originalSource.close();
    }

    private static class WordSplitLowCaseTransformer extends ContentSourceTransformer
    {
        private final SerializedInvoker invoker = new SerializedInvoker();
        private final Queue<Content.Chunk> chunks = new ArrayDeque<>();

        private WordSplitLowCaseTransformer(Content.Source rawSource)
        {
            super(rawSource);
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            super.demand(() -> invoker.run(demandCallback));
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
