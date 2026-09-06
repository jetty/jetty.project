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

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.UnaryOperator;

import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.generator.MessageGenerator;
import org.eclipse.jetty.http3.parser.MessageParser;
import org.eclipse.jetty.http3.parser.ParserListener;
import org.eclipse.jetty.http3.qpack.QpackDecoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RateControl;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.NanoTime;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class DataGenerateParseTest
{
    @Test
    public void testGenerateParseEmpty()
    {
        testGenerateParse(BufferUtil.EMPTY_BUFFER);
    }

    @Test
    public void testGenerateParse()
    {
        byte[] bytes = new byte[1024];
        new Random().nextBytes(bytes);
        testGenerateParse(ByteBuffer.wrap(bytes));
    }

    private void testGenerateParse(ByteBuffer byteBuffer)
    {
        byte[] inputBytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(inputBytes);
        DataFrame input = new DataFrame(ByteBuffer.wrap(inputBytes), true);

        ByteBufferPool bufferPool = ByteBufferPool.NON_POOLING;
        RetainableByteBuffer.Mutable accumulator = new RetainableByteBuffer.DynamicCapacity(bufferPool, true, -1, 0, 0);
        new MessageGenerator(bufferPool, null, true).generate(accumulator, 0, input, null);

        List<DataFrame> frames = new ArrayList<>();
        QpackDecoder decoder = new QpackDecoder(instructions -> {});
        decoder.setBeginNanoTimeSupplier(NanoTime::now);
        MessageParser parser = new MessageParser(RateControl.NO_RATE_CONTROL, new ParserListener()
        {
            @Override
            public void onData(long streamId, DataFrame frame)
            {
                frames.add(frame);
            }
        }, decoder, 13);
        parser.init(UnaryOperator.identity());
        parser.parse(accumulator.getByteBuffer(), false);
        assertFalse(accumulator.hasRemaining());

        assertEquals(1, frames.size());
        DataFrame output = frames.get(0);
        byte[] outputBytes = new byte[output.getByteBuffer().remaining()];
        output.getByteBuffer().get(outputBytes);
        assertArrayEquals(inputBytes, outputBytes);
    }
}
