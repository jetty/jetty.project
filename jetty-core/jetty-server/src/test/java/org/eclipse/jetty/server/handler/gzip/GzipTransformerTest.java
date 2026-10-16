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

package org.eclipse.jetty.server.handler.gzip;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPOutputStream;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.io.content.ChunksContentSource;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.util.compression.InflaterPool;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;

public class GzipTransformerTest
{
    @Test
    public void testTransientFailuresFromOriginalSourceAreReturned() throws Exception
    {
        ArrayByteBufferPool.Tracking trackingBufferPool = new ArrayByteBufferPool.Tracking();
        WritableBufferPool bufferPool = WritableBufferPool.wrap(trackingBufferPool);
        TimeoutException originalFailure1 = new TimeoutException("timeout 1");
        TimeoutException originalFailure2 = new TimeoutException("timeout 2");
        try (TestSource originalSource = new TestSource(
            gzipChunk(bufferPool, "AAA".getBytes(US_ASCII), false),
            Content.Chunk.from(originalFailure1, false),
            gzipChunk(bufferPool, "BBB".getBytes(US_ASCII), false),
            Content.Chunk.from(originalFailure2, false),
            gzipChunk(bufferPool, "CCC".getBytes(US_ASCII), true)
        ))
        {
            GzipRequest.GzipTransformer transformer = new GzipRequest.GzipTransformer(originalSource, new GzipRequest.Decoder(new InflaterPool(1, true), bufferPool, 1));

            try (Content.Chunk chunk = transformer.read())
            {
                try (RetainableByteBuffer buffer = chunk.acquire())
                {
                    assertThat(buffer.getString(US_ASCII), is("AAA"));
                    assertThat(chunk.hasRemaining(), is(false));
                    assertThat(chunk.isLast(), is(false));
                }
            }

            try (Content.Chunk chunk = transformer.read())
            {
                assertThat(Content.Chunk.isFailure(chunk, false), is(true));
                assertThat(chunk.getFailure(), sameInstance(originalFailure1));
            }

            try (Content.Chunk chunk = transformer.read())
            {
                try (RetainableByteBuffer buffer = chunk.acquire())
                {
                    assertThat(buffer.getString(US_ASCII), is("BBB"));
                    assertThat(chunk.hasRemaining(), is(false));
                    assertThat(chunk.isLast(), is(false));
                }
            }

            try (Content.Chunk chunk = transformer.read())
            {
                assertThat(Content.Chunk.isFailure(chunk, false), is(true));
                assertThat(chunk.getFailure(), sameInstance(originalFailure2));
            }

            try (Content.Chunk chunk = transformer.read())
            {
                try (RetainableByteBuffer buffer = chunk.acquire())
                {
                    assertThat(buffer.getString(US_ASCII), is("CCC"));
                    assertThat(chunk.hasRemaining(), is(false));
                    assertThat(chunk.isLast(), is(false));
                }
            }

            try (Content.Chunk chunk = transformer.read())
            {
                assertThat(Content.Chunk.isFailure(chunk), is(false));
                assertThat(chunk.hasRemaining(), is(false));
                assertThat(chunk.isLast(), is(true));
            }
        }
        assertThat("Leaks: " + trackingBufferPool.dumpLeaks(), trackingBufferPool.getLeaks().size(), is(0));
    }

    private static Content.Chunk gzipChunk(WritableBufferPool bufferPool, byte[] bytes, boolean last) throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream gzos = new GZIPOutputStream(baos);
        gzos.write(bytes);
        gzos.close();
        byte[] gzippedBytes = baos.toByteArray();

        try (RetainableByteBuffer.Mutable buffer = bufferPool.acquire(gzippedBytes.length, false))
        {
            buffer.put(gzippedBytes);
            return Content.Chunk.from(buffer, last);
        }
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
                    chunk.release();
                }
                chunks = null;
            }
        }
    }
}
