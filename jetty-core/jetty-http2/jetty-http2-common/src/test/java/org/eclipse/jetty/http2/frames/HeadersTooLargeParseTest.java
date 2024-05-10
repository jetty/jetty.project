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

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http.HostPortHttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpScheme;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.generator.HeaderGenerator;
import org.eclipse.jetty.http2.generator.HeadersGenerator;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class HeadersTooLargeParseTest
{
    private final ByteBufferPool bufferPool = new ArrayByteBufferPool();

    @Test
    public void testProtocolErrorURITooLong() throws Exception
    {
        HttpFields fields = HttpFields.build()
                .put("B", "test");
        MetaData.Request metaData = new MetaData.Request("GET", HttpScheme.HTTP.asString(), new HostPortHttpField("localhost:8080"), "/nested/uri/path/too/long", HttpVersion.HTTP_2, fields, -1);
        int maxHeaderSize = 48;

        assertProtocolError(maxHeaderSize, metaData);
    }

    @Test
    public void testProtocolErrorCumulativeHeaderSize() throws Exception
    {
        HttpFields fields = HttpFields.build()
                .put("X-Large-Header", "lorem-ipsum-dolor-sit")
                .put("X-Other-Header", "test");
        MetaData.Request metaData = new MetaData.Request("GET", HttpScheme.HTTP.asString(), new HostPortHttpField("localhost:8080"), "/", HttpVersion.HTTP_2, fields, -1);
        int maxHeaderSize = 64;

        assertProtocolError(maxHeaderSize, metaData);
    }

    private void assertProtocolError(int maxHeaderSize, MetaData.Request metaData) throws Exception
    {
        HeadersGenerator generator = new HeadersGenerator(new HeaderGenerator(bufferPool), new HpackEncoder());

        AtomicInteger failure = new AtomicInteger();
        Parser parser = new Parser(bufferPool, maxHeaderSize);
        parser.init(new Parser.Listener()
        {
            @Override
            public void onConnectionFailure(int error, String reason)
            {
                failure.set(error);
            }
        });

        int streamId = 48;
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity();
        PriorityFrame priorityFrame = new PriorityFrame(streamId, 3 * streamId, 200, true);
        int len = generator.generateHeaders(accumulator, streamId, metaData, priorityFrame, true);

        Callback.Completable callback = new Callback.Completable();
        accumulator.writeTo((l, b, c) ->
        {
            parser.parse(b);
            if (failure.get() != 0)
                c.failed(new Throwable("Expected"));
            else
                c.succeeded();
        }, false, callback);

        try
        {
            callback.get(10, TimeUnit.SECONDS);
            fail();
        }
        catch (ExecutionException e)
        {
            assertThat(e.getCause().getMessage(), is("Expected"));
        }
        accumulator.release();

        assertTrue(len > maxHeaderSize);
        assertEquals(ErrorCode.PROTOCOL_ERROR.code, failure.get());
    }
}
