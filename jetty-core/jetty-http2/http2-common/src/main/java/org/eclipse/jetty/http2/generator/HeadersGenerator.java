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

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.hpack.HpackException;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class HeadersGenerator extends FrameGenerator
{
    private final HpackEncoder encoder;
    private final int maxHeaderBlockFragment;
    private final PriorityGenerator priorityGenerator;

    public HeadersGenerator(HeaderGenerator headerGenerator, HpackEncoder encoder)
    {
        this(headerGenerator, encoder, 0);
    }

    public HeadersGenerator(HeaderGenerator headerGenerator, HpackEncoder encoder, int maxHeaderBlockFragment)
    {
        super(headerGenerator);
        this.encoder = encoder;
        this.maxHeaderBlockFragment = maxHeaderBlockFragment;
        this.priorityGenerator = new PriorityGenerator(headerGenerator);
    }

    @Override
    public int generate(ByteBufferPool.Lease lease, Frame frame) throws HpackException
    {
        HeadersFrame headersFrame = (HeadersFrame)frame;
        return generateHeaders(lease, headersFrame.getStreamId(), headersFrame.getMetaData(), headersFrame.getPriority(), headersFrame.isEndStream());
    }

    public int generateHeaders(ByteBufferPool.Lease lease, int streamId, MetaData metaData, PriorityFrame priority, boolean endStream) throws HpackException
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);

        int flags = Flags.NONE;

        if (priority != null)
            flags = Flags.PRIORITY;

        ByteBuffer hpacked = encode(encoder, lease, metaData, getMaxFrameSize());
        int hpackedLength = hpacked.position();
        BufferUtil.flipToFlush(hpacked, 0);

        // Split into CONTINUATION frames if necessary.
        if (maxHeaderBlockFragment > 0 && hpackedLength > maxHeaderBlockFragment)
        {
            if (endStream)
                flags |= Flags.END_STREAM;

            int length = maxHeaderBlockFragment;
            if (priority != null)
                length += PriorityFrame.PRIORITY_LENGTH;

            ByteBuffer header = generateHeader(lease, FrameType.HEADERS, length, flags, streamId);
            generatePriority(header, priority);
            BufferUtil.flipToFlush(header, 0);
            lease.append(header, true);
            hpacked.limit(maxHeaderBlockFragment);
            lease.append(hpacked.slice(), false);

            int totalLength = Frame.HEADER_LENGTH + length;

            int position = maxHeaderBlockFragment;
            int limit = position + maxHeaderBlockFragment;
            while (limit < hpackedLength)
            {
                hpacked.position(position).limit(limit);
                header = generateHeader(lease, FrameType.CONTINUATION, maxHeaderBlockFragment, Flags.NONE, streamId);
                BufferUtil.flipToFlush(header, 0);
                lease.append(header, true);
                lease.append(hpacked.slice(), false);
                position += maxHeaderBlockFragment;
                limit += maxHeaderBlockFragment;
                totalLength += Frame.HEADER_LENGTH + maxHeaderBlockFragment;
            }

            hpacked.position(position).limit(hpackedLength);
            header = generateHeader(lease, FrameType.CONTINUATION, hpacked.remaining(), Flags.END_HEADERS, streamId);
            BufferUtil.flipToFlush(header, 0);
            lease.append(header, true);
            lease.append(hpacked, true);
            totalLength += Frame.HEADER_LENGTH + hpacked.remaining();

            return totalLength;
        }
        else
        {
            flags |= Flags.END_HEADERS;
            if (endStream)
                flags |= Flags.END_STREAM;

            int length = hpackedLength;
            if (priority != null)
                length += PriorityFrame.PRIORITY_LENGTH;

            ByteBuffer header = generateHeader(lease, FrameType.HEADERS, length, flags, streamId);
            generatePriority(header, priority);
            BufferUtil.flipToFlush(header, 0);
            lease.append(header, true);
            lease.append(hpacked, true);

            return Frame.HEADER_LENGTH + length;
        }
    }

    private void generatePriority(ByteBuffer header, PriorityFrame priority)
    {
        if (priority != null)
        {
            priorityGenerator.generatePriorityBody(header, priority.getStreamId(),
                priority.getParentStreamId(), priority.getWeight(), priority.isExclusive());
        }
    }
}
