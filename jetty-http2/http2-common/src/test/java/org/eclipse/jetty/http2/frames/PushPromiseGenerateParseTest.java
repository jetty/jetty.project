//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http2.frames;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http2.generator.HeaderGenerator;
import org.eclipse.jetty.http2.generator.PushPromiseGenerator;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.hpack.MetaData;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

public class PushPromiseGenerateParseTest
{
    private final ByteBufferPool byteBufferPool = new MappedByteBufferPool();

    @Ignore
    @Test
    public void testGenerateParse() throws Exception
    {
        PushPromiseGenerator generator = new PushPromiseGenerator(new HeaderGenerator(), new HpackEncoder());

        int streamId = 13;
        int promisedStreamId = 17;
        HttpFields fields = new HttpFields();
        fields.put("Accept", "text/html");
        fields.put("User-Agent", "Jetty");
        MetaData.Request metaData = new MetaData.Request(HttpScheme.HTTP, "GET", "localhost:8080", "localhost", 8080, "/path", fields);

        // Iterate a few times to be sure generator and parser are properly reset.
        final List<PushPromiseFrame> frames = new ArrayList<>();
        for (int i = 0; i < 2; ++i)
        {
            ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
            generator.generatePushPromise(lease, streamId, promisedStreamId, metaData);
            Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
            {
                @Override
                public boolean onPushPromise(PushPromiseFrame frame)
                {
                    frames.add(frame);
                    return false;
                }
            });

            frames.clear();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                while (buffer.hasRemaining())
                {
                    parser.parse(buffer);
                }
            }

            Assert.assertEquals(1, frames.size());
            PushPromiseFrame frame = frames.get(0);
            Assert.assertEquals(streamId, frame.getStreamId());
            Assert.assertEquals(promisedStreamId, frame.getPromisedStreamId());
            MetaData.Request request = (MetaData.Request)frame.getMetaData();
            Assert.assertSame(metaData.getScheme(), request.getScheme());
            Assert.assertEquals(metaData.getMethod(), request.getMethod());
            Assert.assertEquals(metaData.getAuthority(), request.getAuthority());
            Assert.assertEquals(metaData.getHost(), request.getHost());
            Assert.assertEquals(metaData.getPort(), request.getPort());
            Assert.assertEquals(metaData.getPath(), request.getPath());
            for (int j = 0; j < fields.size(); ++j)
            {
                HttpField field = fields.getField(j);
                Assert.assertTrue(request.getFields().contains(field));
            }
        }
    }

    @Test
    public void testGenerateParseOneByteAtATime() throws Exception
    {
        PushPromiseGenerator generator = new PushPromiseGenerator(new HeaderGenerator(), new HpackEncoder());

        int streamId = 13;
        int promisedStreamId = 17;
        HttpFields fields = new HttpFields();
        fields.put("Accept", "text/html");
        fields.put("User-Agent", "Jetty");
        MetaData.Request metaData = new MetaData.Request(HttpScheme.HTTP, "GET", "localhost:8080", "localhost", 8080, "/path", fields);

        final List<PushPromiseFrame> frames = new ArrayList<>();
        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.generatePushPromise(lease, streamId, promisedStreamId, metaData);
        Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
        {
            @Override
            public boolean onPushPromise(PushPromiseFrame frame)
            {
                frames.add(frame);
                return false;
            }
        });

        for (ByteBuffer buffer : lease.getByteBuffers())
        {
            while (buffer.hasRemaining())
            {
                parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
            }
        }

        Assert.assertEquals(1, frames.size());
        PushPromiseFrame frame = frames.get(0);
        Assert.assertEquals(streamId, frame.getStreamId());
        Assert.assertEquals(promisedStreamId, frame.getPromisedStreamId());
        MetaData.Request request = (MetaData.Request)frame.getMetaData();
        Assert.assertSame(metaData.getScheme(), request.getScheme());
        Assert.assertEquals(metaData.getMethod(), request.getMethod());
        Assert.assertEquals(metaData.getAuthority(), request.getAuthority());
        Assert.assertEquals(metaData.getHost(), request.getHost());
        Assert.assertEquals(metaData.getPort(), request.getPort());
        Assert.assertEquals(metaData.getPath(), request.getPath());
        for (int j = 0; j < fields.size(); ++j)
        {
            HttpField field = fields.getField(j);
            Assert.assertTrue(request.getFields().contains(field));
        }
    }
}
