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

package org.eclipse.jetty.client.transport;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.io.Content;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ResponseListenersTest
{
    @Test
    public void testContentSourceDemultiplexerSpuriousWakeup()
    {
        SimpleSource contentSource = new SimpleSource();
        contentSource.add(Content.Chunk.from(ByteBuffer.wrap(new byte[] {1}), false));
        contentSource.addSpuriousWakup();
        contentSource.add(Content.Chunk.from(ByteBuffer.wrap(new byte[] {2}), false));
        contentSource.addSpuriousWakup();
        contentSource.add(Content.Chunk.from(ByteBuffer.wrap(new byte[] {3}), true));

        List<Content.Chunk> chunks = new CopyOnWriteArrayList<>();

        ResponseListeners responseListeners = new ResponseListeners();
        // Add 2 ContentSourceListeners to enable the use of ContentSourceDemultiplexer.
        responseListeners.addContentSourceListener((r, cs) -> new Consumer(cs)
        {
            @Override
            void onChunk(Content.Chunk chunk)
            {
                chunks.add(chunk);
            }
        });
        responseListeners.addContentSourceListener((r, cs) -> new Consumer(cs)
        {
            @Override
            void onChunk(Content.Chunk chunk)
            {
                chunks.add(chunk);
            }
        });
        responseListeners.notifyContentSource(null, contentSource);

        assertThat("Chunks: " + chunks, chunks.size(), is(6));
        assertThat(chunks.get(0).isLast(), is(false));
        assertThat(chunks.get(0).getByteBuffer().get(), is((byte)1));
        assertThat(chunks.get(1).isLast(), is(false));
        assertThat(chunks.get(1).getByteBuffer().get(), is((byte)1));
        assertThat(chunks.get(2).isLast(), is(false));
        assertThat(chunks.get(2).getByteBuffer().get(), is((byte)2));
        assertThat(chunks.get(3).isLast(), is(false));
        assertThat(chunks.get(3).getByteBuffer().get(), is((byte)2));
        assertThat(chunks.get(4).isLast(), is(true));
        assertThat(chunks.get(4).getByteBuffer().get(), is((byte)3));
        assertThat(chunks.get(5).isLast(), is(true));
        assertThat(chunks.get(5).getByteBuffer().get(), is((byte)3));
    }

    private abstract static class Consumer implements Runnable
    {
        private final Content.Source source;

        private Consumer(Content.Source source)
        {
            this.source = source;
            source.demand(this);
        }

        @Override
        public void run()
        {
            Content.Chunk chunk = source.read();
            onChunk(chunk);
            if (chunk == null)
            {
                source.demand(this);
                return;
            }
            chunk.release();
            if (!chunk.isLast())
                source.demand(this);
        }

        abstract void onChunk(Content.Chunk chunk);
    }

    private static class SimpleSource implements Content.Source
    {
        private static final Content.Chunk NULL = new Content.Chunk()
        {
            @Override
            public ByteBuffer getByteBuffer()
            {
                return null;
            }

            @Override
            public boolean isLast()
            {
                return false;
            }
        };
        private final Queue<Content.Chunk> chunks = new ConcurrentLinkedQueue<>();
        private Runnable demand;

        // Add a chunk to be served normally
        void add(Content.Chunk chunk)
        {
            chunks.add(chunk);
            if (demand != null)
            {
                Runnable r = demand;
                demand = null;
                r.run();
            }
        }

        // Add a spurious wakeup
        void addSpuriousWakup()
        {
            chunks.add(NULL);
            if (demand != null)
            {
                Runnable r = demand;
                demand = null;
                r.run();
            }
        }

        @Override
        public Content.Chunk read()
        {
            if (demand != null)
                throw new IllegalStateException();

            Content.Chunk chunk = chunks.poll();
            return chunk == NULL ? null : chunk;
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            if (demand != null)
                throw new IllegalStateException();

            if (!chunks.isEmpty())
                demandCallback.run();
            else
                demand = demandCallback;
        }

        @Override
        public void fail(Throwable failure)
        {
            demand = null;
            while (!chunks.isEmpty())
            {
                Content.Chunk chunk = chunks.poll();
                if (chunk != null)
                    chunk.release();
            }
        }
    }
}
