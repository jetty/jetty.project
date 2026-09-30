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

package org.eclipse.jetty.http3.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.generator.MessageGenerator;
import org.eclipse.jetty.http3.parser.MessageParser;
import org.eclipse.jetty.http3.parser.ParserListener;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.http3.qpack.QpackEncoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RateControl;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.NanoTime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class HeadersGenerateParseTest
{
    @Test
    public void testGenerateParse()
    {
        HttpURI uri = HttpURI.from("http://host:1234/path?a=b");
        HttpFields fields = HttpFields.build()
            .put("User-Agent", "Jetty")
            .put("Cookie", "c=d");
        HeadersFrame input = new HeadersFrame(new MetaData.Request(HttpMethod.GET.asString(), uri, HttpVersion.HTTP_3, fields), true);

        QpackEncoder encoder = new QpackEncoder(instructions -> {});
        encoder.setMaxHeadersSize(4 * 1024);
        ByteBufferPool bufferPool = ByteBufferPool.NON_POOLING;
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(bufferPool, true, -1, 0, 0);
        new MessageGenerator(bufferPool, encoder, true).generate(accumulator, 0, input, null);

        QpackDecoder decoder = new QpackDecoder(instructions -> {});
        decoder.setMaxHeadersSize(4 * 1024);
        decoder.setBeginNanoTimeSupplier(NanoTime::now);
        List<HeadersFrame> frames = new ArrayList<>();
        MessageParser parser = new MessageParser(RateControl.NO_RATE_CONTROL, new ParserListener()
        {
            @Override
            public void onHeaders(long streamId, HeadersFrame frame, boolean wasBlocked)
            {
                frames.add(frame);
            }
        }, decoder, 13);
        parser.init(UnaryOperator.identity());
        parser.parse(accumulator.getByteBuffer(), false);
        assertFalse(accumulator.hasRemaining());

        assertEquals(1, frames.size());
        HeadersFrame output = frames.get(0);

        MetaData.Request inputMetaData = (MetaData.Request)input.getMetaData();
        MetaData.Request outputMetaData = (MetaData.Request)output.getMetaData();
        assertEquals(inputMetaData.getMethod(), outputMetaData.getMethod());
        assertEquals(inputMetaData.getHttpURI().toString(), outputMetaData.getHttpURI().toString());
        assertEquals(inputMetaData.getHttpFields(), outputMetaData.getHttpFields());
    }
}
