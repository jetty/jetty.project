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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.client.TestSource;
import org.eclipse.jetty.io.Content;
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

        // The failures are not alternated because when a ContentSourceDemultiplexer is failed,
        // it immediately services demands, and this particular test does demand right after
        // failing the source.
        // Remember that when a ContentSourceDemultiplexer is failed, it does NOT forward the failure
        // to its original source until ALL ContentSourceDemultiplexers are failed. But in the meantime,
        // it remembers the failure and serves reads and demands with it; so this is the only case when
        // reading from ContentSourceDemultiplexers does not alternate the responses.
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
            Content.Chunk.from(new IOException("boom"), true)
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
        assertThat(chunks.get(4).getFailure(), instanceOf(IOException.class));
        assertThat(Content.Chunk.isFailure(chunks.get(5), true), is(true));
        assertThat(chunks.get(5).getFailure(), instanceOf(IOException.class));

        Content.Chunk chunk = contentSource.read();
        assertThat(Content.Chunk.isFailure(chunk, true), is(true));
        assertThat(chunk.getFailure(), instanceOf(NumberFormatException.class));

        chunks.forEach(Content.Chunk::release);
        contentSource.close();
    }
}
