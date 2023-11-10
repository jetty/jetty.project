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
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeoutException;
import java.util.zip.GZIPOutputStream;

import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.TestSource;
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
        ArrayByteBufferPool.Tracking bufferPool = new ArrayByteBufferPool.Tracking();
        TimeoutException originalFailure1 = new TimeoutException("timeout 1");
        TimeoutException originalFailure2 = new TimeoutException("timeout 2");
        TestSource originalSource = new TestSource(
            Content.Chunk.from(gzip("AAA".getBytes(US_ASCII)), false),
            Content.Chunk.from(originalFailure1, false),
            Content.Chunk.from(gzip("BBB".getBytes(US_ASCII)), false),
            Content.Chunk.from(originalFailure2, false),
            Content.Chunk.from(gzip("CCC".getBytes(US_ASCII)), true)
        );

        GzipRequest.GzipTransformer transformer = new GzipRequest.GzipTransformer(originalSource, new GzipRequest.Decoder(new InflaterPool(1, true), bufferPool, 1));


        Content.Chunk chunk;
        chunk = transformer.read();
        assertThat(US_ASCII.decode(chunk.getByteBuffer()).toString(), is("AAA"));
        assertThat(chunk.getByteBuffer().hasRemaining(), is(false));
        assertThat(chunk.isLast(), is(false));
        chunk.release();

        chunk = transformer.read();
        assertThat(Content.Chunk.isFailure(chunk, false), is(true));
        assertThat(chunk.getFailure(), sameInstance(originalFailure1));

        chunk = transformer.read();
        assertThat(US_ASCII.decode(chunk.getByteBuffer()).toString(), is("BBB"));
        assertThat(chunk.getByteBuffer().hasRemaining(), is(false));
        assertThat(chunk.isLast(), is(false));
        chunk.release();

        chunk = transformer.read();
        assertThat(Content.Chunk.isFailure(chunk, false), is(true));
        assertThat(chunk.getFailure(), sameInstance(originalFailure2));

        chunk = transformer.read();
        assertThat(US_ASCII.decode(chunk.getByteBuffer()).toString(), is("CCC"));
        assertThat(chunk.getByteBuffer().hasRemaining(), is(false));
        assertThat(chunk.isLast(), is(false));
        chunk.release();

        chunk = transformer.read();
        assertThat(Content.Chunk.isFailure(chunk), is(false));
        assertThat(chunk.getByteBuffer().hasRemaining(), is(false));
        assertThat(chunk.isLast(), is(true));

        assertThat("Leaks: " + bufferPool.dumpLeaks(), bufferPool.getLeaks().size(), is(0));
    }

    private static ByteBuffer gzip(byte[] bytes) throws IOException
    {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        GZIPOutputStream gzos = new GZIPOutputStream(baos);
        gzos.write(bytes);
        gzos.close();
        return ByteBuffer.wrap(baos.toByteArray());
    }
}
