//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.internal;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.generator.MessageGenerator;
import org.eclipse.jetty.http3.internal.parser.MessageParser;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.http3.qpack.QpackEncoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.NullByteBufferPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class HeadersGenerateParseTest
{
    @Test
    public void testGenerateParse() throws Exception
    {
        HttpURI uri = HttpURI.from("http://host:1234/path?a=b");
        HttpFields fields = HttpFields.build()
            .put("User-Agent", "Jetty")
            .put("Cookie", "c=d");
        HeadersFrame input = new HeadersFrame(new MetaData.Request(HttpMethod.GET.asString(), uri, HttpVersion.HTTP_3, fields), true);

        QpackEncoder encoder = new QpackEncoder(instructions -> {}, 100);
        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(new NullByteBufferPool());
        new MessageGenerator(encoder, 8192, true).generate(lease, 0, input);

        QpackDecoder decoder = new QpackDecoder(instructions -> {}, 8192);
        List<Frame> frames = new ArrayList<>();
        MessageParser parser = new MessageParser(0, decoder);
        for (ByteBuffer buffer : lease.getByteBuffers())
        {
            while (buffer.hasRemaining())
            {
                Frame frame = parser.parse(buffer);
                if (frame != null)
                    frames.add(frame);
            }
        }

        assertEquals(1, frames.size());
        HeadersFrame output = (HeadersFrame)frames.get(0);

        MetaData.Request inputMetaData = (MetaData.Request)input.getMetaData();
        MetaData.Request outputMetaData = (MetaData.Request)output.getMetaData();
        assertEquals(inputMetaData.getMethod(), outputMetaData.getMethod());
        assertEquals(inputMetaData.getURIString(), outputMetaData.getURIString());
        assertEquals(inputMetaData.getFields(), outputMetaData.getFields());
    }
}
