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

package org.eclipse.jetty.http3.internal;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.UnaryOperator;

import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.internal.generator.MessageGenerator;
import org.eclipse.jetty.http3.internal.parser.MessageParser;
import org.eclipse.jetty.http3.internal.parser.ParserListener;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
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

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(ByteBufferPool.NOOP);
        new MessageGenerator(null, 8192, true).generate(lease, 0, input, null);

        List<DataFrame> frames = new ArrayList<>();
        MessageParser parser = new MessageParser(new ParserListener()
        {
            @Override
            public void onData(long streamId, DataFrame frame)
            {
                frames.add(frame);
            }
        }, null, 13, () -> true);
        parser.init(UnaryOperator.identity());
        for (ByteBuffer buffer : lease.getByteBuffers())
        {
            parser.parse(buffer);
            assertFalse(buffer.hasRemaining());
        }

        assertEquals(1, frames.size());
        DataFrame output = frames.get(0);
        byte[] outputBytes = new byte[output.getByteBuffer().remaining()];
        output.getByteBuffer().get(outputBytes);
        assertArrayEquals(inputBytes, outputBytes);
    }
}
