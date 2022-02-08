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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;

import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.generator.HeaderGenerator;
import org.eclipse.jetty.http2.generator.SettingsGenerator;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SettingsGenerateParseTest
{
    private final ByteBufferPool byteBufferPool = new MappedByteBufferPool();

    @Test
    public void testGenerateParseNoSettings()
    {

        List<SettingsFrame> frames = testGenerateParse(Collections.<Integer, Integer>emptyMap());
        assertEquals(1, frames.size());
        SettingsFrame frame = frames.get(0);
        assertEquals(0, frame.getSettings().size());
        assertTrue(frame.isReply());
    }

    @Test
    public void testGenerateParseSettings()
    {
        Map<Integer, Integer> settings1 = new HashMap<>();
        int key1 = 13;
        Integer value1 = 17;
        settings1.put(key1, value1);
        int key2 = 19;
        Integer value2 = 23;
        settings1.put(key2, value2);
        List<SettingsFrame> frames = testGenerateParse(settings1);
        assertEquals(1, frames.size());
        SettingsFrame frame = frames.get(0);
        Map<Integer, Integer> settings2 = frame.getSettings();
        assertEquals(2, settings2.size());
        assertEquals(value1, settings2.get(key1));
        assertEquals(value2, settings2.get(key2));
    }

    private List<SettingsFrame> testGenerateParse(Map<Integer, Integer> settings)
    {
        SettingsGenerator generator = new SettingsGenerator(new HeaderGenerator());

        List<SettingsFrame> frames = new ArrayList<>();
        Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
        {
            @Override
            public void onSettings(SettingsFrame frame)
            {
                frames.add(frame);
            }
        }, 4096, 8192);
        parser.init(UnaryOperator.identity());

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
            generator.generateSettings(lease, settings, true);

            frames.clear();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                while (buffer.hasRemaining())
                {
                    parser.parse(buffer);
                }
            }
        }

        return frames;
    }

    @Test
    public void testGenerateParseInvalidSettings()
    {
        SettingsGenerator generator = new SettingsGenerator(new HeaderGenerator());

        AtomicInteger errorRef = new AtomicInteger();
        Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
        {
            @Override
            public void onConnectionFailure(int error, String reason)
            {
                errorRef.set(error);
            }
        }, 4096, 8192);
        parser.init(UnaryOperator.identity());

        Map<Integer, Integer> settings1 = new HashMap<>();
        settings1.put(13, 17);
        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.generateSettings(lease, settings1, true);
        // Modify the length of the frame to make it invalid
        ByteBuffer bytes = lease.getByteBuffers().get(0);
        bytes.putShort(1, (short)(bytes.getShort(1) - 1));

        for (ByteBuffer buffer : lease.getByteBuffers())
        {
            while (buffer.hasRemaining())
            {
                parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
            }
        }

        assertEquals(ErrorCode.FRAME_SIZE_ERROR.code, errorRef.get());
    }

    @Test
    public void testGenerateParseOneByteAtATime()
    {
        SettingsGenerator generator = new SettingsGenerator(new HeaderGenerator());

        List<SettingsFrame> frames = new ArrayList<>();
        Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
        {
            @Override
            public void onSettings(SettingsFrame frame)
            {
                frames.add(frame);
            }
        }, 4096, 8192);
        parser.init(UnaryOperator.identity());

        Map<Integer, Integer> settings1 = new HashMap<>();
        int key = 13;
        Integer value = 17;
        settings1.put(key, value);

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
            generator.generateSettings(lease, settings1, true);

            frames.clear();
            for (ByteBuffer buffer : lease.getByteBuffers())
            {
                while (buffer.hasRemaining())
                {
                    parser.parse(ByteBuffer.wrap(new byte[]{buffer.get()}));
                }
            }

            assertEquals(1, frames.size());
            SettingsFrame frame = frames.get(0);
            Map<Integer, Integer> settings2 = frame.getSettings();
            assertEquals(1, settings2.size());
            assertEquals(value, settings2.get(key));
            assertTrue(frame.isReply());
        }
    }

    @Test
    public void testGenerateParseTooManyDifferentSettingsInOneFrame()
    {
        SettingsGenerator generator = new SettingsGenerator(new HeaderGenerator());

        AtomicInteger errorRef = new AtomicInteger();
        Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
        {
            @Override
            public void onConnectionFailure(int error, String reason)
            {
                errorRef.set(error);
            }
        }, 4096, 8192);
        int maxSettingsKeys = 32;
        parser.setMaxSettingsKeys(maxSettingsKeys);
        parser.init(UnaryOperator.identity());

        Map<Integer, Integer> settings = new HashMap<>();
        for (int i = 0; i < maxSettingsKeys + 1; ++i)
        {
            settings.put(i + 10, i);
        }

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        generator.generateSettings(lease, settings, false);

        for (ByteBuffer buffer : lease.getByteBuffers())
        {
            while (buffer.hasRemaining())
            {
                parser.parse(buffer);
            }
        }

        assertEquals(ErrorCode.ENHANCE_YOUR_CALM_ERROR.code, errorRef.get());
    }

    @Test
    public void testGenerateParseTooManySameSettingsInOneFrame() throws Exception
    {
        int keyValueLength = 6;
        int pairs = Frame.DEFAULT_MAX_LENGTH / keyValueLength;
        int maxSettingsKeys = pairs / 2;

        AtomicInteger errorRef = new AtomicInteger();
        Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter(), 4096, 8192);
        parser.setMaxSettingsKeys(maxSettingsKeys);
        parser.setMaxFrameLength(Frame.DEFAULT_MAX_LENGTH);
        parser.init(listener -> new Parser.Listener.Wrapper(listener)
        {
            @Override
            public void onConnectionFailure(int error, String reason)
            {
                errorRef.set(error);
            }
        });

        int length = pairs * keyValueLength;
        ByteBuffer buffer = ByteBuffer.allocate(1 + 9 + length);
        buffer.putInt(length);
        buffer.put((byte)FrameType.SETTINGS.getType());
        buffer.put((byte)0); // Flags.
        buffer.putInt(0); // Stream ID.
        // Add the same setting over and over again.
        for (int i = 0; i < pairs; ++i)
        {
            buffer.putShort((short)SettingsFrame.MAX_CONCURRENT_STREAMS);
            buffer.putInt(i);
        }
        // Only 3 bytes for the length, skip the first.
        buffer.flip().position(1);

        while (buffer.hasRemaining())
        {
            parser.parse(buffer);
        }

        assertEquals(ErrorCode.ENHANCE_YOUR_CALM_ERROR.code, errorRef.get());
    }

    @Test
    public void testGenerateParseTooManySettingsInMultipleFrames()
    {
        SettingsGenerator generator = new SettingsGenerator(new HeaderGenerator());

        AtomicInteger errorRef = new AtomicInteger();
        Parser parser = new Parser(byteBufferPool, new Parser.Listener.Adapter()
        {
            @Override
            public void onConnectionFailure(int error, String reason)
            {
                errorRef.set(error);
            }
        }, 4096, 8192);
        int maxSettingsKeys = 32;
        parser.setMaxSettingsKeys(maxSettingsKeys);
        parser.init(UnaryOperator.identity());

        Map<Integer, Integer> settings = new HashMap<>();
        settings.put(13, 17);

        ByteBufferPool.Lease lease = new ByteBufferPool.Lease(byteBufferPool);
        for (int i = 0; i < maxSettingsKeys + 1; ++i)
        {
            generator.generateSettings(lease, settings, false);
        }

        for (ByteBuffer buffer : lease.getByteBuffers())
        {
            while (buffer.hasRemaining())
            {
                parser.parse(buffer);
            }
        }

        assertEquals(ErrorCode.ENHANCE_YOUR_CALM_ERROR.code, errorRef.get());
    }
}
