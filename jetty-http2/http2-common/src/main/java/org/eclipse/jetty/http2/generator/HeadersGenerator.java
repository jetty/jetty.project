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

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.frames.Flag;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class HeadersGenerator extends FrameGenerator
{
    private final HpackEncoder encoder;

    public HeadersGenerator(HeaderGenerator headerGenerator, HpackEncoder encoder)
    {
        super(headerGenerator);
        this.encoder = encoder;
    }

    @Override
    public void generate(ByteBufferPool.Lease lease, Frame frame)
    {
        HeadersFrame headersFrame = (HeadersFrame)frame;
        generateHeaders(lease, headersFrame.getStreamId(), headersFrame.getMetaData(), !headersFrame.isEndStream());
    }

    public void generateHeaders(ByteBufferPool.Lease lease, int streamId, MetaData metaData, boolean contentFollows)
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);

        encoder.encode(metaData, lease);

        long length = lease.getTotalLength();
        if (length > Frame.MAX_LENGTH)
            throw new IllegalArgumentException("Invalid headers, too big");

        int flags = Flag.END_HEADERS;
        if (!contentFollows)
            flags |= Flag.END_STREAM;

        ByteBuffer header = generateHeader(lease, FrameType.HEADERS, (int)length, flags, streamId);

        BufferUtil.flipToFlush(header, 0);
        lease.prepend(header, true);
    }
}
