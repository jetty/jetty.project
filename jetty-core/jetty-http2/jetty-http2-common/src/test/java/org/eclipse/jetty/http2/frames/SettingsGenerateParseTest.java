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

package org.eclipse.jetty.http2.frames;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.http2.ErrorCode;
import org.eclipse.jetty.http2.generator.HeaderGenerator;
import org.eclipse.jetty.http2.generator.SettingsGenerator;
import org.eclipse.jetty.http2.parser.Parser;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.WritableBufferPool;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SettingsGenerateParseTest
{
    private final WritableBufferPool bufferPool = WritableBufferPool.wrap(new ArrayByteBufferPool());

    @Test
    public void testGenerateParseNoSettings()
    {
        List<SettingsFrame> frames = testGenerateParse(Collections.emptyMap(), true);
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
        List<SettingsFrame> frames = testGenerateParse(settings1, false);
        assertEquals(1, frames.size());
        SettingsFrame frame = frames.get(0);
        Map<Integer, Integer> settings2 = frame.getSettings();
        assertEquals(2, settings2.size());
        assertEquals(value1, settings2.get(key1));
        assertEquals(value2, settings2.get(key2));
    }

    private List<SettingsFrame> testGenerateParse(Map<Integer, Integer> settings, boolean reply)
    {
        SettingsGenerator generator = new SettingsGenerator(new HeaderGenerator(bufferPool));

        List<SettingsFrame> frames = new ArrayList<>();
        Parser parser = new Parser(bufferPool, 8192);
        parser.init(new Parser.Listener()
        {
            @Override
            public void onSettings(SettingsFrame frame)
            {
                frames.add(frame);
            }
        });

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            List<ReadableBuffer> accumulator = new ArrayList<>();
            generator.generateSettings(accumulator, settings, reply);

            frames.clear();
            ReadableBuffer rb = ReadableBuffer.accumulate(accumulator);
            accumulator.forEach(ReadableBuffer::release);
            UnknownParseTest.parse(parser, rb);
            rb.release();
        }

        return frames;
    }

    @Test
    public void testGenerateParseInvalidSettingsOneByteAtATime()
    {
        SettingsGenerator generator = new SettingsGenerator(new HeaderGenerator(bufferPool));

        AtomicInteger errorRef = new AtomicInteger();
        Parser parser = new Parser(bufferPool, 8192);
        parser.init(new Parser.Listener()
        {
            @Override
            public void onConnectionFailure(int error, String reason)
            {
                errorRef.set(error);
            }
        });

        Map<Integer, Integer> settings1 = new HashMap<>();
        settings1.put(13, 17);
        List<ReadableBuffer> accumulator = new ArrayList<>();
        generator.generateSettings(accumulator, settings1, false);
        // Modify the length of the frame to make it invalid
        ReadableBuffer rb = ReadableBuffer.accumulate(accumulator);
        accumulator.forEach(ReadableBuffer::release);
        rb.position(1);
        short aShort = rb.getShort();
        rb.position(0);

        WritableBuffer wb = WritableBuffer.allocate((int)rb.remaining(), false);
        wb.put(rb);
        wb.position(1);
        wb.putShort((short)(aShort - 1));
        wb.position(wb.capacity());
        ReadableBuffer buf = wb.toReadable();

        while (buf.remaining() > 0L)
            parser.parse(ReadableBuffer.wrap(new byte[]{buf.get()}));

        assertEquals(ErrorCode.FRAME_SIZE_ERROR.code, errorRef.get());
    }

    @Test
    public void testGenerateParseOneByteAtATime()
    {
        SettingsGenerator generator = new SettingsGenerator(new HeaderGenerator(bufferPool));

        List<SettingsFrame> frames = new ArrayList<>();
        Parser parser = new Parser(bufferPool, 8192);
        parser.init(new Parser.Listener()
        {
            @Override
            public void onSettings(SettingsFrame frame)
            {
                frames.add(frame);
            }
        });

        Map<Integer, Integer> settings1 = new HashMap<>();
        int key = 13;
        Integer value = 17;
        settings1.put(key, value);

        // Iterate a few times to be sure generator and parser are properly reset.
        for (int i = 0; i < 2; ++i)
        {
            List<ReadableBuffer> accumulator = new ArrayList<>();
            generator.generateSettings(accumulator, settings1, false);

            frames.clear();

            ReadableBuffer rb = ReadableBuffer.accumulate(accumulator);
            accumulator.forEach(ReadableBuffer::release);
            while (rb.remaining() > 0L)
                parser.parse(ReadableBuffer.wrap(new byte[]{rb.get()}));

            assertEquals(1, frames.size());
            SettingsFrame frame = frames.get(0);
            Map<Integer, Integer> settings2 = frame.getSettings();
            assertEquals(1, settings2.size());
            assertEquals(value, settings2.get(key));
            assertFalse(frame.isReply());
        }
    }

    @Test
    public void testGenerateParseTooManyDifferentSettingsInOneFrame()
    {
        SettingsGenerator generator = new SettingsGenerator(new HeaderGenerator(bufferPool));

        AtomicInteger errorRef = new AtomicInteger();
        Parser parser = new Parser(bufferPool, 8192);
        int maxSettingsKeys = 32;
        parser.setMaxSettingsKeys(maxSettingsKeys);
        parser.init(new Parser.Listener()
        {
            @Override
            public void onConnectionFailure(int error, String reason)
            {
                errorRef.set(error);
            }
        });

        Map<Integer, Integer> settings = new HashMap<>();
        for (int i = 0; i < maxSettingsKeys + 1; ++i)
        {
            settings.put(i + 10, i);
        }

        List<ReadableBuffer> accumulator = new ArrayList<>();
        generator.generateSettings(accumulator, settings, false);
        ReadableBuffer rb = ReadableBuffer.accumulate(accumulator);
        accumulator.forEach(ReadableBuffer::release);
        UnknownParseTest.parse(parser, rb);
        rb.release();

        assertEquals(ErrorCode.ENHANCE_YOUR_CALM_ERROR.code, errorRef.get());
    }

    @Test
    public void testGenerateParseTooManySameSettingsInOneFrame() throws Exception
    {
        int keyValueLength = 6;
        int pairs = Frame.DEFAULT_MAX_SIZE / keyValueLength;
        int maxSettingsKeys = pairs / 2;

        AtomicInteger errorRef = new AtomicInteger();
        Parser parser = new Parser(bufferPool, 8192);
        parser.setMaxSettingsKeys(maxSettingsKeys);
        parser.setMaxFrameSize(Frame.DEFAULT_MAX_SIZE);
        parser.init(new Parser.Listener()
        {
            @Override
            public void onConnectionFailure(int error, String reason)
            {
                errorRef.set(error);
            }
        });

        int length = pairs * keyValueLength;
        WritableBuffer buffer = WritableBuffer.allocate(1 + 9 + length, false);
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
        ReadableBuffer rb = buffer.toReadable();
        rb.position(1);

        while (rb.remaining() > 0L)
        {
            parser.parse(rb);
        }

        assertEquals(ErrorCode.ENHANCE_YOUR_CALM_ERROR.code, errorRef.get());
    }

    @Test
    public void testGenerateParseTooManySettingsInMultipleFrames()
    {
        SettingsGenerator generator = new SettingsGenerator(new HeaderGenerator(bufferPool));

        AtomicInteger errorRef = new AtomicInteger();
        Parser parser = new Parser(bufferPool, 8192);
        int maxSettingsKeys = 32;
        parser.setMaxSettingsKeys(maxSettingsKeys);
        parser.init(new Parser.Listener()
        {
            @Override
            public void onConnectionFailure(int error, String reason)
            {
                errorRef.set(error);
            }
        });

        Map<Integer, Integer> settings = new HashMap<>();
        settings.put(13, 17);

        List<ReadableBuffer> accumulator = new ArrayList<>();
        for (int i = 0; i < maxSettingsKeys + 1; ++i)
        {
            generator.generateSettings(accumulator, settings, false);
        }

        ReadableBuffer rb = ReadableBuffer.accumulate(accumulator);
        accumulator.forEach(ReadableBuffer::release);
        UnknownParseTest.parse(parser, rb);
        rb.release();

        assertEquals(ErrorCode.ENHANCE_YOUR_CALM_ERROR.code, errorRef.get());
    }
}
