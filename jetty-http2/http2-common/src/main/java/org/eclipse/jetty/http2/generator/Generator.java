//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.generator;

import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.io.ByteBufferPool;

public class Generator
{
    private final ByteBufferPool byteBufferPool;
    private final int headerTableSize;
    private final HeaderGenerator headerGenerator;
    private final FrameGenerator[] generators;
    private final DataGenerator dataGenerator;

    public Generator(ByteBufferPool byteBufferPool)
    {
        this(byteBufferPool, 4096);
    }

    public Generator(ByteBufferPool byteBufferPool, int headerTableSize)
    {
        this.byteBufferPool = byteBufferPool;
        this.headerTableSize = headerTableSize;

        headerGenerator = new HeaderGenerator();
        HpackEncoder encoder = new HpackEncoder(headerTableSize);

        this.generators = new FrameGenerator[FrameType.values().length];
        this.generators[FrameType.HEADERS.getType()] = new HeadersGenerator(headerGenerator, encoder);
        this.generators[FrameType.PRIORITY.getType()] = new PriorityGenerator(headerGenerator);
        this.generators[FrameType.RST_STREAM.getType()] = new ResetGenerator(headerGenerator);
        this.generators[FrameType.SETTINGS.getType()] = new SettingsGenerator(headerGenerator);
        this.generators[FrameType.PUSH_PROMISE.getType()] = new PushPromiseGenerator(headerGenerator, encoder);
        this.generators[FrameType.PING.getType()] = new PingGenerator(headerGenerator);
        this.generators[FrameType.GO_AWAY.getType()] = new GoAwayGenerator(headerGenerator);
        this.generators[FrameType.WINDOW_UPDATE.getType()] = new WindowUpdateGenerator(headerGenerator);
        this.generators[FrameType.CONTINUATION.getType()] = null; // TODO

        this.dataGenerator = new DataGenerator(headerGenerator);
    }

    public ByteBufferPool getByteBufferPool()
    {
        return byteBufferPool;
    }

    public int getHeaderTableSize()
    {
        return headerTableSize;
    }

    public void setMaxFrameSize(int maxFrameSize)
    {
        headerGenerator.setMaxFrameSize(maxFrameSize);
    }

    public void control(ByteBufferPool.Lease lease, Frame frame)
    {
        generators[frame.getType().getType()].generate(lease, frame);
    }

    public void data(ByteBufferPool.Lease lease, DataFrame frame, int maxLength)
    {
        dataGenerator.generate(lease, frame, maxLength);
    }
}
