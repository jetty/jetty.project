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

package org.eclipse.jetty.http2.generator;

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.hpack.HpackException;
import org.eclipse.jetty.io.RetainableByteBufferPool;

public class Generator
{
    private final RetainableByteBufferPool bufferPool;
    private final HeaderGenerator headerGenerator;
    private final HpackEncoder hpackEncoder;
    private final FrameGenerator[] generators;
    private final DataGenerator dataGenerator;

    public Generator(RetainableByteBufferPool bufferPool)
    {
        this(bufferPool, 4096, 0);
    }

    public Generator(RetainableByteBufferPool bufferPool, int maxDynamicTableSize, int maxHeaderBlockFragment)
    {
        this(bufferPool, true, maxDynamicTableSize, maxHeaderBlockFragment);
    }

    public Generator(RetainableByteBufferPool bufferPool, boolean useDirectByteBuffers, int maxDynamicTableSize, int maxHeaderBlockFragment)
    {
        this.bufferPool = bufferPool;

        headerGenerator = new HeaderGenerator(bufferPool, useDirectByteBuffers);
        hpackEncoder = new HpackEncoder(maxDynamicTableSize);

        this.generators = new FrameGenerator[FrameType.values().length];
        this.generators[FrameType.HEADERS.getType()] = new HeadersGenerator(headerGenerator, hpackEncoder, maxHeaderBlockFragment);
        this.generators[FrameType.PRIORITY.getType()] = new PriorityGenerator(headerGenerator);
        this.generators[FrameType.RST_STREAM.getType()] = new ResetGenerator(headerGenerator);
        this.generators[FrameType.SETTINGS.getType()] = new SettingsGenerator(headerGenerator);
        this.generators[FrameType.PUSH_PROMISE.getType()] = new PushPromiseGenerator(headerGenerator, hpackEncoder);
        this.generators[FrameType.PING.getType()] = new PingGenerator(headerGenerator);
        this.generators[FrameType.GO_AWAY.getType()] = new GoAwayGenerator(headerGenerator);
        this.generators[FrameType.WINDOW_UPDATE.getType()] = new WindowUpdateGenerator(headerGenerator);
        this.generators[FrameType.CONTINUATION.getType()] = null; // Never generated explicitly.
        this.generators[FrameType.PREFACE.getType()] = new PrefaceGenerator();
        this.generators[FrameType.DISCONNECT.getType()] = new NoOpGenerator();

        this.dataGenerator = new DataGenerator(headerGenerator);
    }

    public RetainableByteBufferPool getRetainableByteBufferPool()
    {
        return bufferPool;
    }

    public void setValidateHpackEncoding(boolean validateEncoding)
    {
        hpackEncoder.setValidateEncoding(validateEncoding);
    }

    public void setHeaderTableSize(int headerTableSize)
    {
        hpackEncoder.setRemoteMaxDynamicTableSize(headerTableSize);
    }

    public void setMaxFrameSize(int maxFrameSize)
    {
        headerGenerator.setMaxFrameSize(maxFrameSize);
    }

    public int control(RetainableByteBufferPool.Accumulator accumulator, Frame frame) throws HpackException
    {
        return generators[frame.getType().getType()].generate(accumulator, frame);
    }

    public int data(RetainableByteBufferPool.Accumulator accumulator, DataFrame frame, int maxLength)
    {
        return dataGenerator.generate(accumulator, frame, maxLength);
    }

    public void setMaxHeaderListSize(int value)
    {
        hpackEncoder.setMaxHeaderListSize(value);
    }
}
