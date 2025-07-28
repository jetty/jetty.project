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

package org.eclipse.jetty.http2.generator;

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.hpack.HpackException;
import org.eclipse.jetty.io.ByteBufferPool;

public class Generator
{
    private final ByteBufferPool bufferPool;
    private final HeaderGenerator headerGenerator;
    private final HpackEncoder hpackEncoder;
    private final FrameGenerator[] generators;
    private final PrefaceGenerator prefaceGenerator;
    private final DataGenerator dataGenerator;

    public Generator(ByteBufferPool bufferPool)
    {
        this(bufferPool, 0);
    }

    public Generator(ByteBufferPool bufferPool, int maxHeaderBlockFragment)
    {
        this(bufferPool, true, maxHeaderBlockFragment);
    }

    public Generator(ByteBufferPool bufferPool, boolean useDirectByteBuffers, int maxHeaderBlockFragment)
    {
        this.bufferPool = bufferPool;

        headerGenerator = new HeaderGenerator(bufferPool, useDirectByteBuffers);
        hpackEncoder = new HpackEncoder();

        this.generators = new FrameGenerator[FrameType.CONTINUATION.ordinal() + 1];
        this.generators[FrameType.HEADERS.getType()] = new HeadersGenerator(headerGenerator, hpackEncoder, maxHeaderBlockFragment);
        this.generators[FrameType.PRIORITY.getType()] = new PriorityGenerator(headerGenerator);
        this.generators[FrameType.RST_STREAM.getType()] = new ResetGenerator(headerGenerator);
        this.generators[FrameType.SETTINGS.getType()] = new SettingsGenerator(headerGenerator);
        this.generators[FrameType.PUSH_PROMISE.getType()] = new PushPromiseGenerator(headerGenerator, hpackEncoder);
        this.generators[FrameType.PING.getType()] = new PingGenerator(headerGenerator);
        this.generators[FrameType.GO_AWAY.getType()] = new GoAwayGenerator(headerGenerator);
        this.generators[FrameType.WINDOW_UPDATE.getType()] = new WindowUpdateGenerator(headerGenerator);
        this.generators[FrameType.CONTINUATION.getType()] = null; // Never generated explicitly.
        this.prefaceGenerator = new PrefaceGenerator();
        this.dataGenerator = new DataGenerator(headerGenerator);
    }

    public ByteBufferPool getByteBufferPool()
    {
        return bufferPool;
    }

    public HpackEncoder getHpackEncoder()
    {
        return hpackEncoder;
    }

    public int getMaxFrameSize()
    {
        return headerGenerator.getMaxFrameSize();
    }

    public void setMaxFrameSize(int maxFrameSize)
    {
        headerGenerator.setMaxFrameSize(maxFrameSize);
    }

    public int control(ByteBufferPool.Accumulator accumulator, Frame frame) throws HpackException
    {
        int type = frame.getType().getType();
        if (type == FrameType.PREFACE.getType())
            return prefaceGenerator.generate(accumulator, frame);
        return generators[type].generate(accumulator, frame);
    }

    public int data(ByteBufferPool.Accumulator accumulator, DataFrame frame, int maxLength)
    {
        return dataGenerator.generate(accumulator, frame, maxLength);
    }
}
