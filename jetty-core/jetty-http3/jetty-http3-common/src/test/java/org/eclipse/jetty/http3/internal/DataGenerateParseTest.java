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
import java.util.Random;
import java.util.function.UnaryOperator;

import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.generator.MessageGenerator;
import org.eclipse.jetty.http3.parser.MessageParser;
import org.eclipse.jetty.http3.parser.ParserListener;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class DataGenerateParseTest
{
    @Test
    public void testGenerateParseEmpty()
    {
        testGenerateParse(BufferUtil.EMPTY_BYTES);
    }

    @Test
    public void testGenerateParse()
    {
        byte[] bytes = new byte[1024];
        new Random().nextBytes(bytes);
        testGenerateParse(bytes);
    }

    private void testGenerateParse(byte[] inputBytes)
    {
        DataFrame input = new DataFrame(RetainableByteBuffer.wrap(inputBytes), true);

        WritableBufferPool bufferPool = WritableBufferPool.NON_POOLING;
        List<RetainableByteBuffer> accumulator = new ArrayList<>();
        new MessageGenerator(bufferPool, null, true).generate(accumulator, 0, input, null);

        List<DataFrame> frames = new ArrayList<>();
        QpackDecoder decoder = new QpackDecoder(_ -> {});
        decoder.setBeginNanoTimeSupplier(NanoTime::now);
        MessageParser parser = new MessageParser(new ParserListener()
        {
            @Override
            public void onData(long streamId, DataFrame frame)
            {
                frames.add(frame);
            }
        }, decoder, 13);
        parser.init(UnaryOperator.identity());
        RetainableByteBuffer buffer = RetainableByteBuffer.wrap(accumulator);
        parser.parse(buffer, false);
        assertFalse(buffer.hasRemaining());

        assertEquals(1, frames.size());
        DataFrame output = frames.getFirst();
        byte[] outputBytes = output.acquire().getArray();
        assertArrayEquals(inputBytes, outputBytes);
    }
}
