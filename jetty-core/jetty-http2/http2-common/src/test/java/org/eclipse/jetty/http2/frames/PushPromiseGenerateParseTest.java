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

package org.eclipse.jetty.http2.frames;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.internal.generator.HeaderGenerator;
import org.eclipse.jetty.http2.internal.generator.PushPromiseGenerator;
import org.eclipse.jetty.http2.internal.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PushPromiseGenerateParseTest
{
    private final ByteBufferPool byteBufferPool = new MappedByteBufferPool();

    @Test
    public void testGenerateParse() throws Exception
    {
        PushPromiseGenerator generator = new PushPromiseGenerator(new HeaderGenerator(), new HpackEncoder());

        final List<PushPromiseFrame> frames = new ArrayList<>();
        Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
        {
            @Override
            public void onPushPromise(PushPromiseFrame frame)
            {
                frames.add(frame);
            }
        }, 4096, 8192);
        parser.init(UnaryOperator.identity());

        int streamId = 13;
        int promisedStreamId = 17;
        HttpFields.Mutable fields = HttpFields.build()
            .put("Accept", "text/html")
            .put("User-Agent", "Jetty");
        MetaData.Request metaData = new MetaData.Request("GET", HttpScheme.HTTP.asString(), new HostPortHttpField("localhost:8080"), "/path", HttpVersion.HTTP_2, fields, -1);

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
            generator.generatePushPromise(lease, streamId, promisedStreamId, metaData);

            frames.clear();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                while (buffer.hasRemaining())
                {
                    parser.parse(buffer);
                }
            }

            assertEquals(1, frames.size());
            PushPromiseFrame frame = frames.get(0);
            assertEquals(streamId, frame.getStreamId());
            assertEquals(promisedStreamId, frame.getPromisedStreamId());
            MetaData.Request request = (MetaData.Request)frame.getMetaData();
            assertEquals(metaData.getMethod(), request.getMethod());
            assertEquals(metaData.getURI(), request.getURI());
            for (int j = 0; j < fields.size(); ++j)
            {
                HttpField field = fields.getField(j);
                assertTrue(request.getFields().contains(field));
            }
        }
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        PushPromiseGenerator generator = new PushPromiseGenerator(new HeaderGenerator(), new HpackEncoder());

        final List<PushPromiseFrame> frames = new ArrayList<>();
        Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
        {
            @Override
            public void onPushPromise(PushPromiseFrame frame)
            {
                frames.add(frame);
            }
        }, 4096, 8192);
        parser.init(UnaryOperator.identity());

        int streamId = 13;
        int promisedStreamId = 17;
        HttpFields.Mutable fields = HttpFields.build()
            .put("Accept", "text/html")
            .put("User-Agent", "Jetty");
        MetaData.Request metaData = new MetaData.Request("GET", HttpScheme.HTTP.asString(), new HostPortHttpField("localhost:8080"), "/path", HttpVersion.HTTP_2, fields, -1);

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
            generator.generatePushPromise(lease, streamId, promisedStreamId, metaData);

            frames.clear();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                while (buffer.hasRemaining())
                {
                    parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
                }
            }

            assertEquals(1, frames.size());
            PushPromiseFrame frame = frames.get(0);
            assertEquals(streamId, frame.getStreamId());
            assertEquals(promisedStreamId, frame.getPromisedStreamId());
            MetaData.Request request = (MetaData.Request)frame.getMetaData();
            assertEquals(metaData.getMethod(), request.getMethod());
            assertEquals(metaData.getURI(), request.getURI());
            for (int j = 0; j < fields.size(); ++j)
            {
                HttpField field = fields.getField(j);
                assertTrue(request.getFields().contains(field));
            }
        }
    }
}
