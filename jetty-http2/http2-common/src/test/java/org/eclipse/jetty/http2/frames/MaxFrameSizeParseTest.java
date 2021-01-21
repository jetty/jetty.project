//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MaxFrameSizeParseTest
{
    private final ByteBufferPool byteBufferPool = new MappedByteBufferPool();

    @Test
    public void testMaxFrameSize()
    {
        int maxFrameLength = Frame.DEFAULT_MAX_LENGTH + 16;

        AtomicInteger failure = new AtomicInteger();
        Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
        {
            @Override
            public void onConnectionFailure(int error, String reason)
            {
                failure.set(error);
            }
        }, 4096, 8192);
        parser.setMaxFrameLength(maxFrameLength);
        parser.init(UnaryOperator.identity());

        // Iterate a few times to be sure the parser is properly reset.
        for (int i = 0; i < 2; ++i)
        {
            byte[] bytes = new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0};
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            buffer.putInt(0, maxFrameLength + 1);
            buffer.position(1);
            while (buffer.hasRemaining())
            {
                parser.parse(buffer);
            }
        }

        assertEquals(ErrorCode.FRAME_SIZE_ERROR.code, failure.get());
    }
}
