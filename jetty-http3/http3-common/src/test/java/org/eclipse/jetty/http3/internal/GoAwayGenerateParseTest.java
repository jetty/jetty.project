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

import org.eclipse.jetty.http3.frames.GoAwayFrame;
import org.eclipse.jetty.http3.internal.generator.ControlGenerator;
import org.eclipse.jetty.http3.internal.parser.ControlParser;
import org.eclipse.jetty.http3.internal.parser.ParserListener;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.NullByteBufferPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class GoAwayGenerateParseTest
{
    @Test
    public void testGenerateParse()
    {
        GoAwayFrame input = GoAwayFrame.CLIENT_GRACEFUL;

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(new NullByteBufferPool());
        new ControlGenerator(true).generate(lease, 0, input, null);

        List<GoAwayFrame> frames = new ArrayList<>();
        ControlParser parser = new ControlParser(new ParserListener()
        {
            @Override
            public void onGoAway(GoAwayFrame frame)
            {
                frames.add(frame);
            }
        });
        for (ByteBuffer buffer : lease.getByteBuffers())
        {
            parser.parse(buffer);
            assertFalse(buffer.hasRemaining());
        }

        assertEquals(1, frames.size());
        GoAwayFrame output = frames.get(0);

        assertEquals(input.getLastId(), output.getLastId());
    }
}
