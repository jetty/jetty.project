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

package org.eclipse.jetty.http2.internal.generator;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.hpack.HpackException;
import org.eclipse.jetty.http2.internal.Flags;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.RetainableByteBufferPool;
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
    public int generate(RetainableByteBufferPool.Accumulator accumulator, Frame frame) throws HpackException
    {
        HeadersFrame headersFrame = (HeadersFrame)frame;
        return generateHeaders(accumulator, headersFrame.getStreamId(), headersFrame.getMetaData(), headersFrame.getPriority(), headersFrame.isEndStream());
    }

    public int generateHeaders(RetainableByteBufferPool.Accumulator accumulator, int streamId, MetaData metaData, PriorityFrame priority, boolean endStream) throws HpackException
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);

        int flags = Flags.NONE;

        if (priority != null)
            flags = Flags.PRIORITY;

        RetainableByteBuffer hpack = encode(encoder, metaData, getMaxFrameSize());
        ByteBuffer hpackByteBuffer = hpack.getByteBuffer();
        int hpackLength = hpackByteBuffer.position();
        BufferUtil.flipToFlush(hpackByteBuffer, 0);

        // Split into CONTINUATION frames if necessary.
        if (maxHeaderBlockFragment > 0 && hpackLength > maxHeaderBlockFragment)
        {
            if (endStream)
                flags |= Flags.END_STREAM;

            int length = maxHeaderBlockFragment;
            if (priority != null)
                length += PriorityFrame.PRIORITY_LENGTH;

            RetainableByteBuffer header = generateHeader(FrameType.HEADERS, length, flags, streamId);
            ByteBuffer headerByteBuffer = header.getByteBuffer();
            generatePriority(headerByteBuffer, priority);
            BufferUtil.flipToFlush(headerByteBuffer, 0);
            accumulator.append(header);
            hpackByteBuffer.limit(maxHeaderBlockFragment);
            accumulator.append(RetainableByteBuffer.wrap(hpackByteBuffer.slice()));

            int totalLength = Frame.HEADER_LENGTH + length;

            int position = maxHeaderBlockFragment;
            int limit = position + maxHeaderBlockFragment;
            while (limit < hpackLength)
            {
                hpackByteBuffer.position(position).limit(limit);
                header = generateHeader(FrameType.CONTINUATION, maxHeaderBlockFragment, Flags.NONE, streamId);
                headerByteBuffer = header.getByteBuffer();
                BufferUtil.flipToFlush(headerByteBuffer, 0);
                accumulator.append(header);
                accumulator.append(RetainableByteBuffer.wrap(hpackByteBuffer.slice()));
                position += maxHeaderBlockFragment;
                limit += maxHeaderBlockFragment;
                totalLength += Frame.HEADER_LENGTH + maxHeaderBlockFragment;
            }

            hpackByteBuffer.position(position).limit(hpackLength);
            header = generateHeader(FrameType.CONTINUATION, hpack.remaining(), Flags.END_HEADERS, streamId);
            headerByteBuffer = header.getByteBuffer();
            BufferUtil.flipToFlush(headerByteBuffer, 0);
            accumulator.append(header);
            accumulator.append(hpack);
            totalLength += Frame.HEADER_LENGTH + hpack.remaining();

            return totalLength;
        }
        else
        {
            flags |= Flags.END_HEADERS;
            if (endStream)
                flags |= Flags.END_STREAM;

            int length = hpackLength;
            if (priority != null)
                length += PriorityFrame.PRIORITY_LENGTH;

            RetainableByteBuffer header = generateHeader(FrameType.HEADERS, length, flags, streamId);
            ByteBuffer headerByteBuffer = header.getByteBuffer();
            generatePriority(headerByteBuffer, priority);
            BufferUtil.flipToFlush(headerByteBuffer, 0);
            accumulator.append(header);
            accumulator.append(hpack);

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
