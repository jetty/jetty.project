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

package org.eclipse.jetty.server;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Queue;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

public class HttpStreamTest
{
    @Test
    public void testNoContentReturnsContentNotConsumed()
    {
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setMaxUnconsumedRequestContentReads(2);
        TestHttpStream httpStream = new TestHttpStream();
        Throwable throwable = HttpStream.consumeAvailable(httpStream, httpConfig);
        assertThat(throwable, notNullValue());
    }

    @Test
    public void testTooMuchContentReturnsContentNotConsumed()
    {
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setMaxUnconsumedRequestContentReads(2);
        TestHttpStream httpStream = new TestHttpStream(
            Content.Chunk.from(ByteBuffer.wrap(new byte[]
            {1}), false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[]
            {2}), false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[]
            {3}), true));
        Throwable throwable = HttpStream.consumeAvailable(httpStream, httpConfig);
        assertThat(throwable, notNullValue());
    }

    @Test
    public void testLastContentReturnsNull()
    {
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setMaxUnconsumedRequestContentReads(5);
        TestHttpStream httpStream = new TestHttpStream(
            Content.Chunk.from(ByteBuffer.wrap(new byte[]
            {1}), false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[]
            {2}), false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[]
            {3}), true));
        Throwable throwable = HttpStream.consumeAvailable(httpStream, httpConfig);
        assertThat(throwable, nullValue());
    }

    @Test
    public void testTerminalFailureReturnsFailure()
    {
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setMaxUnconsumedRequestContentReads(5);
        NumberFormatException failure = new NumberFormatException();
        TestHttpStream httpStream = new TestHttpStream(
            Content.Chunk.from(ByteBuffer.wrap(new byte[]
            {1}), false), Content.Chunk.from(failure, true));
        Throwable throwable = HttpStream.consumeAvailable(httpStream, httpConfig);
        assertThat(throwable, sameInstance(failure));
    }

    @Test
    public void testTransientFailureReturnsFailure()
    {
        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setMaxUnconsumedRequestContentReads(5);
        NumberFormatException failure = new NumberFormatException();
        TestHttpStream httpStream = new TestHttpStream(
            Content.Chunk.from(ByteBuffer.wrap(new byte[]
            {1}), false),
            Content.Chunk.from(failure, false),
            Content.Chunk.from(ByteBuffer.wrap(new byte[]
            {2}), true));
        Throwable throwable = HttpStream.consumeAvailable(httpStream, httpConfig);
        assertThat(throwable, sameInstance(failure));
    }

    private static class TestHttpStream implements HttpStream
    {
        private final Queue<Content.Chunk> chunks = new ArrayDeque<>();

        public TestHttpStream(Content.Chunk... chunks)
        {
            this.chunks.addAll(Arrays.asList(chunks));
        }

        @Override
        public Content.Chunk read()
        {
            return chunks.poll();
        }

        @Override
        public String getId()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void demand()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void prepareResponse(HttpFields.Mutable headers)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void send(
                         MetaData.Request request,
                         MetaData.Response response,
                         boolean last,
                         ByteBuffer content,
                         Callback callback)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getIdleTimeout()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setIdleTimeout(long idleTimeoutMs)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isCommitted()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Throwable consumeAvailable()
        {
            throw new UnsupportedOperationException();
        }
    }
}
