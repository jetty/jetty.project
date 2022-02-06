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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class UnknownParseTest
{
    private final ByteBufferPool byteBufferPool = new MappedByteBufferPool();

    @Test
    public void testParse()
    {
        testParse(Function.identity());
    }

    @Test
    public void testParseOneByteAtATime()
    {
        testParse(buffer -> ByteBuffer.wrap(new byte[]{buffer.get()}));
    }

    @Test
    public void testInvalidFrameSize()
    {
        AtomicInteger failure = new AtomicInteger();
        Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter(), 4096, 8192);
        parser.init(listener -> new Parser.Listener.Wrapper(listener)
        {
            @Override
            public void onConnectionFailure(int error, String reason)
            {
                failure.set(error);
            }
        });
        parser.setMaxFrameLength(Frame.DEFAULT_MAX_LENGTH);

        // 0x4001 == 16385 which is > Frame.DEFAULT_MAX_LENGTH.
        byte[] bytes = new byte[]{0, 0x40, 0x01, 64, 0, 0, 0, 0, 0};
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining())
        {
            parser.parse(buffer);
        }

        assertEquals(ErrorCode.FRAME_SIZE_ERROR.code, failure.get());
    }

    private void testParse(Function<ByteBuffer, ByteBuffer> fn)
    {
        AtomicBoolean failure = new AtomicBoolean();
        Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
        {
            @Override
            public void onConnectionFailure(int error, String reason)
            {
                failure.set(true);
            }
        }, 4096, 8192);
        parser.init(UnaryOperator.identity());

        // Iterate a few times to be sure the parser is properly reset.
        for (int i = 0; i < 2; ++i)
        {
            byte[] bytes = new byte[]{0, 0, 4, 64, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining())
            {
                parser.parse(fn.apply(buffer));
            }
        }

        assertFalse(failure.get());
    }
}
