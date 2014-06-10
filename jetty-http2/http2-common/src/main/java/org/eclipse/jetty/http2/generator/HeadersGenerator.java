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

import org.eclipse.jetty.http2.frames.Flag;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.hpack.MetaData;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

public class HeadersGenerator extends FrameGenerator
{
    private final HpackEncoder encoder;

    public HeadersGenerator(HeaderGenerator headerGenerator, HpackEncoder encoder)
    {
        super(headerGenerator);
        this.encoder = encoder;
    }

    @Override
    public void generate(ByteBufferPool.Lease lease, Frame frame, Callback callback)
    {
        HeadersFrame headersFrame = (HeadersFrame)frame;
        generate(lease, headersFrame.getStreamId(), headersFrame.getMetaData(), !headersFrame.isEndStream(), null);
    }

    private void generate(ByteBufferPool.Lease lease, int streamId, MetaData metaData, boolean contentFollows, byte[] paddingBytes)
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);
        int paddingLength = paddingBytes == null ? 0 : paddingBytes.length;
        // Leave space for at least one byte of content.
        if (paddingLength > Frame.MAX_LENGTH - 3)
            throw new IllegalArgumentException("Invalid padding length: " + paddingLength);

        int extraPaddingBytes = paddingLength > 0xFF ? 2 : paddingLength > 0 ? 1 : 0;

        encoder.encode(metaData, lease);

        long hpackLength = lease.getTotalLength();

        long length = extraPaddingBytes + hpackLength + paddingLength;
        if (length > Frame.MAX_LENGTH)
            throw new IllegalArgumentException("Invalid headers, too big");

        int flags = Flag.END_HEADERS;
        if (!contentFollows)
            flags |= Flag.END_STREAM;
        if (extraPaddingBytes > 0)
            flags |= Flag.PADDING_LOW;
        if (extraPaddingBytes > 1)
            flags |= Flag.PADDING_HIGH;

        ByteBuffer header = generateHeader(lease, FrameType.HEADERS, Frame.HEADER_LENGTH + extraPaddingBytes, (int)length, flags, streamId);

        if (extraPaddingBytes == 2)
            header.putShort((short)paddingLength);
        else if (extraPaddingBytes == 1)
            header.put((byte)paddingLength);

        BufferUtil.flipToFlush(header, 0);
        lease.prepend(header, true);

        if (paddingBytes != null)
        {
            lease.append(ByteBuffer.wrap(paddingBytes), false);
        }
    }
}
