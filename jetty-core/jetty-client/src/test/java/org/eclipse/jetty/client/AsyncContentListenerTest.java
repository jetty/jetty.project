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

package org.eclipse.jetty.client;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

import java.io.Closeable;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.eclipse.jetty.client.transport.HttpConversation;
import org.eclipse.jetty.client.transport.HttpRequest;
import org.eclipse.jetty.client.transport.HttpResponse;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ChunksContentSource;
import org.junit.jupiter.api.Test;

public class AsyncContentListenerTest
{
    @Test
    public void testTransientFailureBecomesTerminal()
    {
        TestSource originalSource = new TestSource(
            Content.Chunk.from(ByteBuffer.wrap(new byte[]
            {1}), false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[]
            {2}), false),
            Content.Chunk.from(new NumberFormatException(), false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[]
            {3}), true));

        List<Content.Chunk> collectedChunks = new ArrayList<>();
        Response.AsyncContentListener asyncContentListener = (response, chunk, demander) ->
        {
            chunk.retain();
            collectedChunks.add(chunk);
            demander.run();
        };

        HttpResponse response = new HttpResponse(
            new HttpRequest(new HttpClient(), new HttpConversation(), URI.create("http://localhost")));
        asyncContentListener.onContentSource(response, originalSource);

        assertThat(collectedChunks.size(), is(2));
        assertThat(collectedChunks.get(0).isLast(), is(false));
        assertThat(collectedChunks.get(0).getByteBuffer().get(), is((byte)1));
        assertThat(collectedChunks.get(0).getByteBuffer().hasRemaining(), is(false));
        assertThat(collectedChunks.get(1).isLast(), is(false));
        assertThat(collectedChunks.get(1).getByteBuffer().get(), is((byte)2));
        assertThat(collectedChunks.get(1).getByteBuffer().hasRemaining(), is(false));

        Content.Chunk chunk = originalSource.read();
        assertThat(Content.Chunk.isFailure(chunk, true), is(true));
        assertThat(chunk.getFailure(), instanceOf(NumberFormatException.class));

        collectedChunks.forEach(Content.Chunk::release);
        originalSource.close();
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
