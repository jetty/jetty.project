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

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.internal.HttpContentResponse;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ChunksContentSource;
import org.eclipse.jetty.util.BufferUtil;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

public class ResponseListenersTest
{
    @Test
    public void testContentSourceDemultiplexerSpuriousWakeup()
    {
        TestSource contentSource = new TestSource(
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{1}), false),
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{2}), false),
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{3}), true)
        );

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

        chunks.forEach(Content.Chunk::release);
        contentSource.close();
    }

    @Test
    public void testContentSourceDemultiplexerFailOnTransientException()
    {
        TestSource contentSource = new TestSource(
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{1}), false),
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{2}), false),
            null,
            Content.Chunk.from(new TimeoutException("timeout"), false),
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{3}), true)
        );

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
                    if (Content.Chunk.isFailure(chunk, false))
                        source.fail(new NumberFormatException());
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

        assertThat(chunks.size(), is(8));
        assertThat(chunks.get(0).getByteBuffer().get(), is((byte)1));
        assertThat(chunks.get(0).isLast(), is(false));
        assertThat(chunks.get(1).getByteBuffer().get(), is((byte)1));
        assertThat(chunks.get(1).isLast(), is(false));
        assertThat(chunks.get(2).getByteBuffer().get(), is((byte)2));
        assertThat(chunks.get(2).isLast(), is(false));
        assertThat(chunks.get(3).getByteBuffer().get(), is((byte)2));
        assertThat(chunks.get(3).isLast(), is(false));

        // Failures are not alternated because ContentSourceDemultiplexer is failed,
        // it immediately services demands.
        assertThat(Content.Chunk.isFailure(chunks.get(4), false), is(true));
        assertThat(chunks.get(4).getFailure(), instanceOf(TimeoutException.class));
        assertThat(Content.Chunk.isFailure(chunks.get(5), true), is(true));
        assertThat(chunks.get(5).getFailure(), instanceOf(NumberFormatException.class));
        assertThat(Content.Chunk.isFailure(chunks.get(6), false), is(true));
        assertThat(chunks.get(6).getFailure(), instanceOf(TimeoutException.class));
        assertThat(Content.Chunk.isFailure(chunks.get(7), true), is(true));
        assertThat(chunks.get(7).getFailure(), instanceOf(NumberFormatException.class));

        Content.Chunk chunk = contentSource.read();
        assertThat(Content.Chunk.isFailure(chunk, true), is(true));
        assertThat(chunk.getFailure(), instanceOf(NumberFormatException.class));

        chunks.forEach(Content.Chunk::release);
        contentSource.close();
    }

    @Test
    public void testContentSourceDemultiplexerFailOnTerminalException()
    {
        TestSource contentSource = new TestSource(
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{1}), false),
            null,
            Content.Chunk.from(ByteBuffer.wrap(new byte[]{2}), false),
            null,
            Content.Chunk.from(new ArithmeticException(), true)
        );

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
                    if (Content.Chunk.isFailure(chunk))
                        source.fail(new NumberFormatException());
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

        assertThat(chunks.size(), is(6));
        assertThat(chunks.get(0).getByteBuffer().get(), is((byte)1));
        assertThat(chunks.get(0).isLast(), is(false));
        assertThat(chunks.get(1).getByteBuffer().get(), is((byte)1));
        assertThat(chunks.get(1).isLast(), is(false));
        assertThat(chunks.get(2).getByteBuffer().get(), is((byte)2));
        assertThat(chunks.get(2).isLast(), is(false));
        assertThat(chunks.get(3).getByteBuffer().get(), is((byte)2));
        assertThat(chunks.get(3).isLast(), is(false));
        assertThat(Content.Chunk.isFailure(chunks.get(4), true), is(true));
        assertThat(chunks.get(4).getFailure(), instanceOf(ArithmeticException.class));
        assertThat(Content.Chunk.isFailure(chunks.get(5), true), is(true));
        assertThat(chunks.get(5).getFailure(), instanceOf(ArithmeticException.class));

        Content.Chunk chunk = contentSource.read();
        assertThat(Content.Chunk.isFailure(chunk, true), is(true));
        assertThat(chunk.getFailure(), instanceOf(ArithmeticException.class));
        assertThat(chunk.getFailure().getSuppressed().length, is(2));
        assertThat(chunk.getFailure().getSuppressed()[0], instanceOf(NumberFormatException.class));
        assertThat(chunk.getFailure().getSuppressed()[1], instanceOf(NumberFormatException.class));

        chunks.forEach(Content.Chunk::release);
        contentSource.close();
    }

    @Test
    public void testEmitEventsInvokesContentSourceListenerForNoContent()
    {
        ResponseListeners responseListeners = new ResponseListeners();
        List<String> events = new ArrayList<>();
        responseListeners.addListener(new Response.Listener()
        {
            @Override
            public void onBegin(Response response)
            {
                events.add("BEGIN");
            }

            @Override
            public boolean onHeader(Response response, HttpField field)
            {
                return events.add("HEADER");
            }

            @Override
            public void onHeaders(Response response)
            {
                events.add("HEADERS");
            }

            @Override
            public void onContentSource(Response response, Content.Source contentSource)
            {
                events.add("CONTENT-SOURCE");
            }

            @Override
            public void onSuccess(Response response)
            {
                events.add("SUCCESS");
            }
        });

        Response response = new HttpResponse(null).addHeader(HttpFields.CONTENT_LENGTH_0);
        Response contentResponse = new HttpContentResponse(response, BufferUtil.EMPTY_BYTES, null, null);
        responseListeners.emitSuccess(contentResponse);

        List<String> expected = List.of("BEGIN", "HEADER", "HEADERS", "CONTENT-SOURCE", "SUCCESS");
        assertThat(events, is(expected));
    }

    private static class TestSource extends ChunksContentSource implements Closeable
    {
        private Content.Chunk[] chunks;

        public TestSource(Content.Chunk... chunks)
        {
            super(Arrays.asList(chunks));
            this.chunks = chunks;
        }

        @Override
        public void close()
        {
            if (chunks != null)
            {
                for (Content.Chunk chunk : chunks)
                {
                    if (chunk != null)
                        chunk.release();
                }
                chunks = null;
            }
        }
    }
}
