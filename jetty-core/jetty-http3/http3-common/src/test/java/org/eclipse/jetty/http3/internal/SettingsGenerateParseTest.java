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
import java.util.Map;

import org.eclipse.jetty.http3.frames.SettingsFrame;
import org.eclipse.jetty.http3.internal.generator.ControlGenerator;
import org.eclipse.jetty.http3.internal.parser.ControlParser;
import org.eclipse.jetty.http3.internal.parser.ParserListener;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.NoopByteBufferPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class SettingsGenerateParseTest
{
    @Test
    public void testGenerateParseEmpty()
    {
        testGenerateParse(Map.of());
    }

    @Test
    public void testGenerateParse()
    {
        testGenerateParse(Map.of(13L, 7L, 31L, 29L));
    }

    private void testGenerateParse(Map<Long, Long> settings)
    {
        SettingsFrame input = new SettingsFrame(settings);

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(new NoopByteBufferPool());
        new ControlGenerator(true).generate(lease, 0, input, null);

        List<SettingsFrame> frames = new ArrayList<>();
        ControlParser parser = new ControlParser(new ParserListener()
        {
            @Override
            public void onSettings(SettingsFrame frame)
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
        SettingsFrame output = frames.get(0);

        assertEquals(input.getSettings(), output.getSettings());
    }
}
