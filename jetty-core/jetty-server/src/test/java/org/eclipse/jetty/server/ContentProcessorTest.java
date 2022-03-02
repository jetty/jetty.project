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

package org.eclipse.jetty.server;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.FutureCallback;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContentProcessorTest
{
    TestProvider _provider;
    TestProcessor _processor;

    @BeforeEach
    public void beforeEach()
    {
        _provider = new TestProvider();
        _processor = new TestProcessor(_provider);
    }

    @AfterEach
    public void afterEach()
    {
        _provider.leakCheck();
    }

    @Test
    public void testNoContent()
    {
        assertThat(_processor.readContent(), nullValue());
        assertThat(_provider.takeDemand(), nullValue());
        assertThat(_processor.readContent(), nullValue());
        assertThat(_provider.takeDemand(), nullValue());

        FutureCallback oca = new FutureCallback();
        _processor.demandContent(oca::succeeded);
        assertFalse(oca.isDone());
        Runnable demand = _provider.takeDemand();
        assertNotNull(demand);
        assertThat(_provider.takeDemand(), nullValue());

        demand.run(); // spurious wakeup!
        assertFalse(oca.isDone());
        demand = _provider.takeDemand();
        assertNotNull(demand);

        assertThat(_processor.readContent(), nullValue());
        assertThat(_provider.takeDemand(), nullValue());

        _provider.add(Content.EOF);
        demand.run();
        assertTrue(oca.isDone());
        assertThat(_processor.readContent(), sameInstance(Content.EOF));
    }

    @Test
    public void testContentAvailableEOF()
    {
        _provider.add("one", false);
        _provider.add("two", false);
        _provider.add("three", false);
        _provider.add(Content.EOF);

        Content content = _processor.readContent();
        assertNotNull(content);
        assertThat(BufferUtil.toString(content.getByteBuffer()), is("one"));
        content.release();

        content = _processor.readContent();
        assertNotNull(content);
        assertThat(BufferUtil.toString(content.getByteBuffer()), is("two"));
        content.release();

        FutureCallback oca = new FutureCallback();
        _processor.demandContent(oca::succeeded);
        assertTrue(oca.isDone());

        content = _processor.readContent();
        assertNotNull(content);
        assertThat(BufferUtil.toString(content.getByteBuffer()), is("three"));
        content.release();

        assertThat(_processor.readContent(), sameInstance(Content.EOF));
    }

    @Test
    public void testContentAvailableLast()
    {
        _provider.add("one", false);
        _provider.add("two", false);
        _provider.add("three", true);

        Content content = _processor.readContent();
        assertNotNull(content);
        assertThat(BufferUtil.toString(content.getByteBuffer()), is("one"));
        content.release();

        content = _processor.readContent();
        assertNotNull(content);
        assertThat(BufferUtil.toString(content.getByteBuffer()), is("two"));
        content.release();

        FutureCallback oca = new FutureCallback();
        _processor.demandContent(oca::succeeded);
        assertTrue(oca.isDone());

        content = _processor.readContent();
        assertNotNull(content);
        assertThat(BufferUtil.toString(content.getByteBuffer()), is("three"));
        assertTrue(content.isLast());
        content.release();

        assertThat(_processor.readContent(), sameInstance(Content.EOF));
    }

    public static Stream<Consumer> consumers()
    {
        return Stream.of(new OneByOneConsumer(), new IteratingConsumer());
    }

    @ParameterizedTest
    @MethodSource("consumers")
    public void testAvailableNoRecursion(Consumer consumer)
    {
        consumer.setProcessor(_processor);
        _provider.add("one", false);
        _provider.add("NOOP", false);
        _provider.add("NOOP TWO NOOP", false);
        _provider.add("THREE", false);
        _provider.add("four five", true);
        _processor.demandContent(consumer);
        assertTrue(consumer.last.get());
        assertFalse(consumer.notReEntrant.get());
        assertThat(consumer.output, contains("one", "two", "three", "four", "five"));
    }

    @ParameterizedTest
    @MethodSource("consumers")
    public void testDemandedNoRecursion(Consumer consumer)
    {
        consumer.setProcessor(_processor);
        _processor.demandContent(consumer);

        _provider.add("one", false);
        _provider.takeDemand().run();
        _provider.add("NOOP", false);
        _provider.takeDemand().run();
        _provider.add("NOOP TWO NOOP", false);
        _provider.takeDemand().run();
        _provider.add("THREE", false);
        _provider.takeDemand().run();
        _provider.add("four five", true);
        _provider.takeDemand().run();

        assertTrue(consumer.last.get());
        assertFalse(consumer.notReEntrant.get());
        assertThat(consumer.output, contains("one", "two", "three", "four", "five"));
    }

    @Test
    public void testProducerThrowsInRead()
    {
        _provider.add("one", false);
        _provider.add("THROW", false);
        _provider.add("two", false);
        _provider.add("THROW", true);

        Content content = _processor.readContent();
        assertNotNull(content);
        assertThat(BufferUtil.toString(content.getByteBuffer()), is("one"));
        content.release();

        content = _processor.readContent();
        assertNotNull(content);
        assertThat(content, instanceOf(Content.Error.class));

        content = _processor.readContent();
        assertNotNull(content);
        assertThat(BufferUtil.toString(content.getByteBuffer()), is("two"));
        content.release();

        content = _processor.readContent();
        assertNotNull(content);
        assertThat(content, instanceOf(Content.Error.class));

        assertThat(_processor.readContent(), sameInstance(Content.EOF));
    }

    @Test
    public void testProducerThrowsInDemand()
    {
        _provider.add("one", false);
        _provider.add("THROW", false);
        _provider.add("two", false);
        _provider.add("THROW", true);

        Content content = _processor.readContent();
        assertNotNull(content);
        assertThat(BufferUtil.toString(content.getByteBuffer()), is("one"));
        content.release();

        FutureCallback oca = new FutureCallback();
        _processor.demandContent(oca::succeeded);
        assertTrue(oca.isDone());
        content = _processor.readContent();
        assertNotNull(content);
        assertThat(content, instanceOf(Content.Error.class));

        content = _processor.readContent();
        assertNotNull(content);
        assertThat(BufferUtil.toString(content.getByteBuffer()), is("two"));
        content.release();

        oca = new FutureCallback();
        _processor.demandContent(oca::succeeded);
        assertTrue(oca.isDone());
        content = _processor.readContent();
        assertNotNull(content);
        assertThat(content, instanceOf(Content.Error.class));

        assertThat(_processor.readContent(), sameInstance(Content.EOF));
    }

    @Test
    public void testProducerThrowsInAvailable()
    {
        _provider.add("one", false);

        Content content = _processor.readContent();
        assertNotNull(content);
        assertThat(BufferUtil.toString(content.getByteBuffer()), is("one"));
        content.release();

        FutureCallback oca = new FutureCallback();
        _processor.demandContent(oca::succeeded);
        assertFalse(oca.isDone());
        Runnable demand = _provider.takeDemand();
        assertNotNull(demand);

        _provider.add("THROW", false);
        _provider.add("two", false);
        demand.run();
        assertTrue(oca.isDone());
        content = _processor.readContent();
        assertNotNull(content);
        assertThat(content, instanceOf(Content.Error.class));

        content = _processor.readContent();
        assertNotNull(content);
        assertThat(BufferUtil.toString(content.getByteBuffer()), is("two"));
        content.release();

        oca = new FutureCallback();
        _processor.demandContent(oca::succeeded);
        assertFalse(oca.isDone());
        demand = _provider.takeDemand();
        assertNotNull(demand);

        _provider.add("THROW", true);
        demand.run();
        assertTrue(oca.isDone());
        content = _processor.readContent();
        assertNotNull(content);
        assertThat(content, instanceOf(Content.Error.class));

        assertThat(_processor.readContent(), sameInstance(Content.EOF));
    }

    @Test
    public void testAvailableThrows()
    {
        AtomicReference<Throwable> error = new AtomicReference<>();
        Deque<String> output = new ConcurrentLinkedDeque<>();
        Runnable onAvailable = new Runnable()
        {
            @Override
            public void run()
            {
                Content content = _processor.readContent();
                if (content != null)
                {
                    if (content.hasRemaining())
                    {
                        String s = BufferUtil.toString(content.getByteBuffer());
                        content.release();
                        if ("throw".equals(s))
                            throw new RuntimeException("testing");
                        if ("dthrow".equals(s))
                        {
                            _processor.demandContent(this);
                            throw new RuntimeException("testing");
                        }
                        output.add(s);
                    }
                    if (content instanceof Content.Error)
                        error.set(((Content.Error)content).getCause());
                    if (content.isLast())
                        return;
                }
                _processor.demandContent(this);
            }
        };

        _processor.demandContent(onAvailable);
        Runnable demand = _provider.takeDemand();
        assertNotNull(demand);
        _provider.add("one", false);
        demand.run();
        assertThat(output, contains("one"));
        assertNull(error.get());

        demand = _provider.takeDemand();
        assertNotNull(demand);
        _provider.add("throw", false);
        demand.run();
        assertThat(output, contains("one"));
        assertNull(error.get());

        _processor.demandContent(onAvailable);
        assertThat(output, contains("one"));
        assertThat(error.getAndSet(null), instanceOf(RuntimeException.class));

        _provider.add("two", false);
        _provider.takeDemand().run();
        assertThat(output, contains("one", "two"));
        assertNull(error.get());

        _provider.add("dthrow", true);
        _provider.takeDemand().run();
        assertThat(output, contains("one", "two"));
        assertThat(error.getAndSet(null), instanceOf(RuntimeException.class));

        assertThat(_processor.readContent(), sameInstance(Content.EOF));
    }

    @Test
    public void testExampleAvailable()
    {
        _provider.add("one", false);
        _provider.add("TWO", false);
        _provider.add("three four", false);
        _provider.add("NOOP", false);
        _provider.add("five NOOP six", false);
        _provider.add(Content.EOF);

        for (String s : List.of("one", "two", "three", "four", "five", "six"))
        {
            Content content = _processor.readContent();
            assertNotNull(content);
            assertThat(BufferUtil.toString(content.getByteBuffer()), is(s));
            content.release();
        }
        assertThat(_processor.readContent(), sameInstance(Content.EOF));
    }

    @Test
    public void testExampleDemandedBefore()
    {
        Deque<String> input = new ArrayDeque<>(List.of("one", "TWO", "three four", "NOOP", "five NOOP six"));

        for (String s : List.of("one", "two", "three", "four", "five", "six"))
        {
            Content content = _processor.readContent();
            if (content == null)
            {
                FutureCallback oca = new FutureCallback();
                _processor.demandContent(oca::succeeded);
                assertFalse(oca.isDone());
                Runnable demand = _provider.takeDemand();
                assertNotNull(demand);
                String word = input.poll();
                _provider.add(word, false);
                demand.run();
                if ("NOOP".equals(word))
                {
                    assertFalse(oca.isDone());
                    demand = _provider.takeDemand();
                    assertNotNull(demand);
                    word = input.poll();
                    _provider.add(word, false);
                    demand.run();
                }
                assertTrue(oca.isDone());
                content = _processor.readContent();
            }
            assertNotNull(content);
            assertThat(BufferUtil.toString(content.getByteBuffer()), is(s));
            content.release();
        }

        _provider.add(Content.EOF);
        assertThat(_processor.readContent(), sameInstance(Content.EOF));
    }

    @Test
    public void testExampleDemandedAfter()
    {
        Deque<String> input = new ArrayDeque<>(List.of("one", "TWO", "three four", "NOOP", "five NOOP six"));

        for (String s : List.of("one", "two", "three", "four", "five", "six"))
        {
            Content content = _processor.readContent();
            if (content == null)
            {
                FutureCallback oca = new FutureCallback();
                String word = input.poll();
                _provider.add(word, false);

                _processor.demandContent(oca::succeeded);
                if ("NOOP".equals(word))
                {
                    assertFalse(oca.isDone());
                    word = input.poll();
                    _provider.add(word, false);
                    _provider.takeDemand().run();
                }
                assertTrue(oca.isDone());
                assertNull(_provider.takeDemand());
                content = _processor.readContent();
            }
            assertNotNull(content);
            assertThat(BufferUtil.toString(content.getByteBuffer()), is(s));
            content.release();
        }

        _provider.add(Content.EOF);
        assertThat(_processor.readContent(), sameInstance(Content.EOF));
    }

    static class TestProvider implements Content.Provider
    {
        final AtomicReference<Runnable> _demand = new AtomicReference<>();
        final Deque<Content> _content = new ConcurrentLinkedDeque<>();
        final Deque<AtomicBoolean> _references = new ConcurrentLinkedDeque<>();

        Runnable takeDemand()
        {
            return _demand.getAndSet(null);
        }

        void add(Content content)
        {
            _content.add(content);
        }

        void add(String content, boolean last)
        {
            AtomicBoolean reference = new AtomicBoolean();
            _references.add(reference);
            ByteBuffer buffer = BufferUtil.toBuffer(content);
            _content.add(new Content.Abstract(false, last)
            {
                @Override
                public void release()
                {
                    reference.set(true);
                }

                @Override
                public ByteBuffer getByteBuffer()
                {
                    return buffer;
                }
            });
        }

        @Override
        public Content readContent()
        {
            Content content = _content.poll();
            Content next = Content.next(content);
            if (next != null)
            {
                _content.clear();
                _content.add(next);
            }
            return content;
        }

        @Override
        public void demandContent(Runnable onContentAvailable)
        {
            if (!_demand.compareAndSet(null, onContentAvailable))
                throw new IllegalStateException();
        }

        public void leakCheck()
        {
            _references.forEach(b -> assertTrue(b.get()));
        }
    }

    static class TestProcessor extends ContentProcessor
    {
        Deque<String> _words = new ArrayDeque<>();
        boolean _last;

        public TestProcessor(Content.Provider provider)
        {
            super(provider);
        }

        @Override
        protected Content process(Content content)
        {
            if (content != null)
            {
                if (!_words.isEmpty())
                    throw new IllegalStateException("enough already!");

                _last |= content.isLast();

                if (content.isSpecial() || content.isEmpty())
                    return content;

                ByteBuffer buffer = content.getByteBuffer();
                boolean space = false;
                boolean upper = false;
                for (int i = buffer.position(); i < buffer.limit(); i++)
                {
                    byte b = buffer.get(i);
                    space |= Character.isWhitespace(b);
                    upper |= Character.isUpperCase(b);
                }
                if (!space && !upper)
                    return content;

                String s = BufferUtil.toString(buffer);
                content.release();

                if (space)
                    _words.addAll(Arrays.asList(s.split("\\s+")));
                else
                    _words.add(s);
            }

            while (true)
            {
                String word = _words.poll();
                if (word == null)
                    return null;
                if ("NOOP".equals(word))
                    continue;
                if ("THROW".equals(word))
                    throw new RuntimeException("testing");
                return Content.from(BufferUtil.toBuffer(word.toLowerCase()), _last && _words.isEmpty());
            }
        }
    }

    private abstract static class Consumer implements Runnable
    {
        final AtomicBoolean last = new AtomicBoolean();
        final List<String> output = new ArrayList<>();
        final AtomicBoolean notReEntrant = new AtomicBoolean();

        Content.Processor _processor;

        void setProcessor(Content.Processor processor)
        {
            _processor = processor;
        }
    }

    private static class OneByOneConsumer extends Consumer
    {
        @Override
        public void run()
        {
            if (!notReEntrant.compareAndSet(false, true))
                throw new IllegalStateException("Reentered!!!!");

            boolean okExit;
            try
            {
                Content content = _processor.readContent();
                if (content != null)
                {
                    if (content.isLast())
                        last.set(true);
                    if (content.hasRemaining())
                        output.add(BufferUtil.toString(content.getByteBuffer()));
                    content.release();
                }
                if (!last.get())
                    _processor.demandContent(this);
            }
            finally
            {
                okExit = notReEntrant.compareAndSet(true, false);
            }

            if (!okExit)
                throw new IllegalStateException("Reexited!?!?");
        }
    }

    private static class IteratingConsumer extends Consumer
    {
        @Override
        public void run()
        {
            if (!notReEntrant.compareAndSet(false, true))
                throw new IllegalStateException("Reentered!!!!");

            boolean okExit;
            try
            {
                Content content = _processor.readContent();
                while (content != null && !last.get())
                {
                    if (content.isLast())
                        last.set(true);
                    if (content.hasRemaining())
                        output.add(BufferUtil.toString(content.getByteBuffer()));
                    content.release();
                    content = _processor.readContent();
                }
                if (!last.get())
                    _processor.demandContent(this);
            }
            finally
            {
                okExit = notReEntrant.compareAndSet(true, false);
            }

            if (!okExit)
                throw new IllegalStateException("Reexited!?!?");
        }
    }
}
