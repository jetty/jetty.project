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

package org.eclipse.jetty.http3.qpack;

import java.nio.ByteBuffer;
import java.util.Random;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EvictionTest
{
    private QpackEncoder _encoder;
    private QpackDecoder _decoder;
    private final TestDecoderHandler _decoderHandler = new TestDecoderHandler();
    private final TestEncoderHandler _encoderHandler = new TestEncoderHandler();
    private final Random random = new Random();

    private static final int MAX_BLOCKED_STREAMS = 5;
    private static final int MAX_HEADER_SIZE = 1024;

    @BeforeEach
    public void before()
    {
        _decoder = new QpackDecoder(_decoderHandler, MAX_HEADER_SIZE);
        _encoder = new QpackEncoder(_encoderHandler, MAX_BLOCKED_STREAMS)
        {
            @Override
            protected boolean shouldHuffmanEncode(HttpField httpField)
            {
                return false;
            }
        };
    }

    @Test
    public void test() throws Exception
    {
        _encoder.setCapacity(1024);
        ByteBuffer encodedFields = ByteBuffer.allocate(1024);

        for (int i = 0; i < 10000; i++)
        {
            HttpFields httpFields = newRandomFields(5);
            long streamId = getPositiveInt(10);

            _encoder.encode(encodedFields, streamId, new MetaData(HttpVersion.HTTP_3, httpFields));
            _decoder.parseInstructions(_encoderHandler.getInstructionBuffer());

            encodedFields.flip();
            _decoder.decode(streamId, encodedFields, _decoderHandler);
            _encoder.parseInstructions(_decoderHandler.getInstructionBuffer());

            MetaData result = _decoderHandler.getMetaData();
            assertNotNull(result);

//            System.err.println("encoder: ");
//            System.err.println(_encoder.dump());
//            System.err.println();
//            System.err.println("decoder: ");
//            System.err.println(_decoder.dump());
//            System.err.println();
//            System.err.println("====================");
//            System.err.println();

            assertTrue(result.getFields().isEqualTo(httpFields));
            encodedFields.clear();
        }
    }

    public HttpFields newRandomFields(int size)
    {
        HttpFields.Mutable fields = HttpFields.build();
        for (int i = 0; i < size; i++)
        {
            fields.add(newRandomField());
        }
        return fields;
    }

    public HttpField newRandomField()
    {
        String header = "header" + getPositiveInt(999);
        String value = "value" + getPositiveInt(999);
        return new HttpField(header, value);
    }

    public int getPositiveInt(int max)
    {
        return Math.abs(random.nextInt(max));
    }
}
