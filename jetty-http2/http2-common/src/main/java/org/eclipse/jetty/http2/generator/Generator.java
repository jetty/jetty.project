//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
    private final HeaderGenerator headerGenerator;
    private final HpackEncoder hpackEncoder;
    private final FrameGenerator[] generators;
    private final DataGenerator dataGenerator;

    public Generator(ByteBufferPool byteBufferPool)
    {
        this(byteBufferPool, 4096, 0);
    }

    public Generator(ByteBufferPool byteBufferPool, int maxDynamicTableSize, int maxHeaderBlockFragment)
    {
        this.byteBufferPool = byteBufferPool;

        headerGenerator = new HeaderGenerator();
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
        this.generators[FrameType.DISCONNECT.getType()] = new DisconnectGenerator();

        this.dataGenerator = new DataGenerator(headerGenerator);
    }

    public ByteBufferPool getByteBufferPool()
    {
        return byteBufferPool;
    }

    public void setHeaderTableSize(int headerTableSize)
    {
        hpackEncoder.setRemoteMaxDynamicTableSize(headerTableSize);
    }

    public void setMaxFrameSize(int maxFrameSize)
    {
        headerGenerator.setMaxFrameSize(maxFrameSize);
    }

    public int control(ByteBufferPool.Lease lease, Frame frame)
    {
        return generators[frame.getType().getType()].generate(lease, frame);
    }

    public int data(ByteBufferPool.Lease lease, DataFrame frame, int maxLength)
    {
        return dataGenerator.generate(lease, frame, maxLength);
    }

    public void setMaxHeaderListSize(int value)
    {
        hpackEncoder.setMaxHeaderListSize(value);
    }
}
