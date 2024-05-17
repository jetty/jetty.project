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

package org.eclipse.jetty.io.content;

import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.io.Content;
import org.reactivestreams.tck.TestEnvironment;
import org.reactivestreams.tck.flow.FlowPublisherVerification;
import org.testng.annotations.Test;

@Test
public final class ContentSourcePublisherTest extends FlowPublisherVerification<Content.Chunk>
{
    public ContentSourcePublisherTest()
    {
        super(new TestEnvironment());
    }

    @Override
    public Flow.Publisher<Content.Chunk> createFlowPublisher(long elements)
    {
        Content.Source source = new SyntheticContentSource(elements);
        return new ContentSourcePublisher(source);
    }

    @Override
    public Flow.Publisher<Content.Chunk> createFailedFlowPublisher()
    {
        Content.Source source = new SyntheticContentSource(0);
        Flow.Publisher<Content.Chunk> publisher = new ContentSourcePublisher(source);
        // Simulate exhausted Content.Source
        publisher.subscribe(new Flow.Subscriber<>()
        {
            @Override
            public void onSubscribe(Flow.Subscription subscription)
            {
                subscription.cancel();
            }

            @Override
            public void onNext(Content.Chunk item)
            {
            }

            @Override
            public void onError(Throwable throwable)
            {
            }

            @Override
            public void onComplete()
            {
            }
        });
        return publisher;
    }

    private static final class SyntheticContentSource implements Content.Source
    {
        private final AtomicReference<State> state;
        private final long contentSize;

        public SyntheticContentSource(long chunksToRead)
        {
            this.state = new AtomicReference<>(new State.Reading(chunksToRead));
            this.contentSize = State.Reading.chunkSize * Math.max(chunksToRead, 0);
        }

        @Override
        public long getLength()
        {
            return contentSize;
        }

        @Override
        public Content.Chunk read()
        {
            return state.getAndUpdate(before -> before.read()).chunk();
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            // TODO: recursive stack overflow
            demandCallback.run();
        }

        @Override
        public void fail(Throwable failure)
        {
            fail(failure, true);
        }

        @Override
        public void fail(Throwable failure, boolean last)
        {
            state.getAndUpdate(before -> before.fail(failure, last));
        }

        @Override
        public boolean rewind()
        {
            return false;
        }

        private sealed interface State permits State.Reading, State.ReadFailed, State.ReadCompleted
        {
            Content.Chunk chunk();

            State read();

            State fail(Throwable failure, boolean last);

            final class Reading implements State
            {
                public static final int chunkSize = 16;
                private static final Random random = new Random();

                private final long chunksToRead;
                private final Content.Chunk chunk;

                public Reading(long chunksToRead)
                {
                    this.chunksToRead = chunksToRead;
                    this.chunk = generateValidChunk(chunksToRead);
                }

                public Reading(long chunksToRead, Throwable transientFailure)
                {
                    this.chunksToRead = chunksToRead;
                    this.chunk = generateFailureChunk(transientFailure);
                }

                @Override
                public Content.Chunk chunk()
                {
                    return chunk;
                }

                @Override
                public State read()
                {
                    long leftToRead = leftToRead();
                    if (leftToRead <= 0)
                        return new ReadCompleted();
                    return new Reading(leftToRead);
                }

                @Override
                public State fail(Throwable failure, boolean last)
                {
                    if (last)
                        return new ReadFailed(failure);
                    return new Reading(chunksToRead, failure);
                }

                private long leftToRead()
                {
                    if (chunksToRead == Long.MAX_VALUE) // endless source
                        return chunksToRead;
                    return chunksToRead - 1;
                }

                private static Content.Chunk generateFailureChunk(Throwable transientFailure)
                {
                    return Content.Chunk.from(transientFailure, false);
                }

                private static Content.Chunk generateValidChunk(long chunksToRead)
                {
                    if (chunksToRead <= 0)
                        return Content.Chunk.EOF;
                    if (chunksToRead == 1)
                        return Content.Chunk.from(randomPayload(), true);
                    return Content.Chunk.from(randomPayload(), false);
                }

                private static ByteBuffer randomPayload()
                {
                    byte[] payload = new byte[chunkSize];
                    random.nextBytes(payload);
                    return ByteBuffer.wrap(payload);
                }
            }

            final class ReadFailed implements State
            {
                private final Content.Chunk chunk;

                public ReadFailed(Throwable failure)
                {
                    this.chunk = Content.Chunk.from(failure, true);
                }

                @Override
                public Content.Chunk chunk()
                {
                    return chunk;
                }

                @Override
                public State read()
                {
                    return this;
                }

                @Override
                public State fail(Throwable failure, boolean last)
                {
                    return this;
                }
            }

            final class ReadCompleted implements State
            {
                @Override
                public Content.Chunk chunk()
                {
                    return Content.Chunk.EOF;
                }

                @Override
                public State read()
                {
                    return this;
                }

                @Override
                public State fail(Throwable failure, boolean last)
                {
                    return this;
                }
            }
        }
    }
}
