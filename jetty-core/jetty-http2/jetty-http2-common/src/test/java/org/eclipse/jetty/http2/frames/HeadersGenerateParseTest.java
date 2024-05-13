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

package org.eclipse.jetty.http2.frames;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.generator.HeaderGenerator;
import org.eclipse.jetty.http2.generator.HeadersGenerator;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HeadersGenerateParseTest
{
    private final ByteBufferPool bufferPool = new ArrayByteBufferPool();

    @Test
    public void testGenerateParse() throws Exception
    {
        HeadersGenerator generator = new HeadersGenerator(new HeaderGenerator(bufferPool), new HpackEncoder());

        int streamId = 13;
        HttpFields fields = HttpFields.build()
            .put("Accept", "text/html")
            .put("User-Agent", "Jetty");
        MetaData.Request metaData = new MetaData.Request("GET", HttpScheme.HTTP.asString(), new HostPortHttpField("localhost:8080"), "/path", HttpVersion.HTTP_2, fields, -1);

        final List<HeadersFrame> frames = new ArrayList<>();
        Parser parser = new Parser(bufferPool, 8192);
        parser.init(new Parser.Listener()
        {
            @Override
            public void onHeaders(HeadersFrame frame)
            {
                frames.add(frame);
            }
        });

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity();
            PriorityFrame priorityFrame = new PriorityFrame(streamId, 3 * streamId, 200, true);
            generator.generateHeaders(accumulator, streamId, metaData, priorityFrame, true);

            frames.clear();
            UnknownParseTest.parse(parser, accumulator);
            accumulator.release();

            assertEquals(1, frames.size());
            HeadersFrame frame = frames.get(0);
            assertEquals(streamId, frame.getStreamId());
            assertTrue(frame.isEndStream());
            MetaData.Request request = (MetaData.Request)frame.getMetaData();
            assertEquals(metaData.getMethod(), request.getMethod());
            assertEquals(metaData.getHttpURI(), request.getHttpURI());
            for (int j = 0; j < fields.size(); ++j)
            {
                HttpField field = fields.getField(j);
                assertTrue(request.getHttpFields().contains(field));
            }
            PriorityFrame priority = frame.getPriority();
            assertNotNull(priority);
            assertEquals(priorityFrame.getStreamId(), priority.getStreamId());
            assertEquals(priorityFrame.getParentStreamId(), priority.getParentStreamId());
            assertEquals(priorityFrame.getWeight(), priority.getWeight());
            assertEquals(priorityFrame.isExclusive(), priority.isExclusive());
        }
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        HeadersGenerator generator = new HeadersGenerator(new HeaderGenerator(bufferPool), new HpackEncoder());

        final List<HeadersFrame> frames = new ArrayList<>();
        Parser parser = new Parser(bufferPool, 8192);
        parser.init(new Parser.Listener()
        {
            @Override
            public void onHeaders(HeadersFrame frame)
            {
                frames.add(frame);
            }
        });

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            int streamId = 13;
            HttpFields.Mutable fields = HttpFields.build()
                .put("Accept", "text/html")
                .put("User-Agent", "Jetty");
            MetaData.Request metaData = new MetaData.Request("GET", HttpScheme.HTTP.asString(), new HostPortHttpField("localhost:8080"), "/path", HttpVersion.HTTP_2, fields, -1);

            RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity();
            PriorityFrame priorityFrame = new PriorityFrame(streamId, 3 * streamId, 200, true);
            generator.generateHeaders(accumulator, streamId, metaData, priorityFrame, true);

            frames.clear();
            UnknownParseTest.parse(parser, accumulator);
            accumulator.release();

            assertEquals(1, frames.size());
            HeadersFrame frame = frames.get(0);
            assertEquals(streamId, frame.getStreamId());
            assertTrue(frame.isEndStream());
            MetaData.Request request = (MetaData.Request)frame.getMetaData();
            assertEquals(metaData.getMethod(), request.getMethod());
            assertEquals(metaData.getHttpURI(), request.getHttpURI());
            for (int j = 0; j < fields.size(); ++j)
            {
                HttpField field = fields.getField(j);
                assertTrue(request.getHttpFields().contains(field));
            }
            PriorityFrame priority = frame.getPriority();
            assertNotNull(priority);
            assertEquals(priorityFrame.getStreamId(), priority.getStreamId());
            assertEquals(priorityFrame.getParentStreamId(), priority.getParentStreamId());
            assertEquals(priorityFrame.getWeight(), priority.getWeight());
            assertEquals(priorityFrame.isExclusive(), priority.isExclusive());
        }
    }
}
