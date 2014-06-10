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

import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.Callback;

public class Generator
{
    private final ByteBufferPool byteBufferPool;
    private final FrameGenerator[] generators;

    public Generator(ByteBufferPool byteBufferPool)
    {
        this.byteBufferPool = byteBufferPool;

        HeaderGenerator headerGenerator = new HeaderGenerator();
        HpackEncoder encoder = new HpackEncoder();

        this.generators = new FrameGenerator[FrameType.values().length];
        this.generators[FrameType.DATA.getType()] = new DataGenerator(headerGenerator);
        this.generators[FrameType.HEADERS.getType()] = new HeadersGenerator(headerGenerator, encoder);
        this.generators[FrameType.PRIORITY.getType()] = new PriorityGenerator(headerGenerator);
        this.generators[FrameType.RST_STREAM.getType()] = new ResetGenerator(headerGenerator);
        this.generators[FrameType.SETTINGS.getType()] = new SettingsGenerator(headerGenerator);
        this.generators[FrameType.PUSH_PROMISE.getType()] = null; // TODO
        this.generators[FrameType.PING.getType()] = new PingGenerator(headerGenerator);
        this.generators[FrameType.GO_AWAY.getType()] = new GoAwayGenerator(headerGenerator);
        this.generators[FrameType.WINDOW_UPDATE.getType()] = new WindowUpdateGenerator(headerGenerator);
        this.generators[FrameType.CONTINUATION.getType()] = null; // TODO
        this.generators[FrameType.ALTSVC.getType()] = null; // TODO
        this.generators[FrameType.BLOCKED.getType()] = null; // TODO

    }

    public LeaseCallback generate(Frame frame, Callback callback)
    {
        LeaseCallback lease = new LeaseCallback(byteBufferPool, callback);
        generators[frame.getType().getType()].generate(lease, frame, callback);
        return lease;
    }

    public static class LeaseCallback extends ByteBufferPool.Lease implements Callback
    {
        private final Callback callback;

        public LeaseCallback(ByteBufferPool byteBufferPool, Callback callback)
        {
            super(byteBufferPool);
            this.callback = callback;
        }

        @Override
        public void succeeded()
        {
            recycle();
            callback.succeeded();
        }

        @Override
        public void failed(Throwable x)
        {
            recycle();
            callback.failed(x);
        }
    }
}
