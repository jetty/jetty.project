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
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.io.Content;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class ResponseListenersTest
{
    @Test
    public void testContentSourceDemultiplexerSpuriousWakeup()
    {
        SimpleSource contentSource = new SimpleSource(Arrays.asList(
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{1}), false),
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{2}), false),
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{3}), true)
        ));

        List<Content.Chunk> chunks = new CopyOnWriteArrayList<>();

        ResponseListeners responseListeners = new ResponseListeners();
        Response.ContentSourceListener contentSourceListener = (r, source) ->
        {
            Runnable runnable = new Runnable()
            {
                @Override
                public void run()
                {
                    Content.Chunk chunk = source.read();
                    chunks.add(chunk);
                    if (chunk == null)
                    {
                        source.demand(this);
                        return;
                    }
                    chunk.release();
                    if (!chunk.isLast())
                        source.demand(this);
                }
            };
            source.demand(runnable);
        };
        // Add 2 ContentSourceListeners to enable the use of ContentSourceDemultiplexer.
        responseListeners.addContentSourceListener(contentSourceListener);
        responseListeners.addContentSourceListener(contentSourceListener);

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

    private static class SimpleSource implements Content.Source
    {
        private static final Content.Chunk SPURIOUS_WAKEUP = new Content.Chunk()
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

        public SimpleSource(List<Content.Chunk> chunks)
        {
            for (Content.Chunk chunk : chunks)
            {
                this.chunks.add(chunk != null ? chunk : SPURIOUS_WAKEUP);
            }
        }

        @Override
        public Content.Chunk read()
        {
            if (demand != null)
                throw new IllegalStateException();

            Content.Chunk chunk = chunks.poll();
            return chunk == SPURIOUS_WAKEUP ? null : chunk;
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
