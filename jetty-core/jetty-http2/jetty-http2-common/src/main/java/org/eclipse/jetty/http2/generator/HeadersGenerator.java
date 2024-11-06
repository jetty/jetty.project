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
import org.eclipse.jetty.io.RetainableByteBuffer;
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
    public int generate(ByteBufferPool.Accumulator accumulator, Frame frame) throws HpackException
    {
        HeadersFrame headersFrame = (HeadersFrame)frame;
        return generateHeaders(accumulator, headersFrame.getStreamId(), headersFrame.getMetaData(), headersFrame.getPriority(), headersFrame.isEndStream());
    }

    public int generateHeaders(ByteBufferPool.Accumulator accumulator, int streamId, MetaData metaData, PriorityFrame priority, boolean endStream) throws HpackException
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);

        int flags = Flags.NONE;
        if (priority != null)
            flags = Flags.PRIORITY;
        if (endStream)
            flags |= Flags.END_STREAM;

        RetainableByteBuffer hpack = encode(encoder, metaData);
        ByteBuffer hpackByteBuffer = hpack.getByteBuffer();
        BufferUtil.flipToFlush(hpackByteBuffer, 0);
        int hpackLength = hpackByteBuffer.remaining();

        int maxHeaderBlock = getMaxFrameSize();
        if (maxHeaderBlockFragment > 0)
            maxHeaderBlock = Math.min(maxHeaderBlock, maxHeaderBlockFragment);

        // Split into CONTINUATION frames if necessary.
        if (hpackLength > maxHeaderBlock)
        {
            int length = maxHeaderBlock;
            if (priority != null)
                length += PriorityFrame.PRIORITY_LENGTH;

            RetainableByteBuffer header = generateHeader(FrameType.HEADERS, length, flags, streamId);
            ByteBuffer headerByteBuffer = header.getByteBuffer();
            generatePriority(headerByteBuffer, priority);
            BufferUtil.flipToFlush(headerByteBuffer, 0);
            accumulator.append(header);
            accumulator.append(RetainableByteBuffer.wrap(hpackByteBuffer.slice(0, maxHeaderBlock)));

            int totalLength = Frame.HEADER_LENGTH + length;

            int position = maxHeaderBlock;
            while (position + maxHeaderBlock < hpackLength)
            {
                header = generateHeader(FrameType.CONTINUATION, maxHeaderBlock, Flags.NONE, streamId);
                BufferUtil.flipToFlush(header.getByteBuffer(), 0);
                accumulator.append(header);
                accumulator.append(RetainableByteBuffer.wrap(hpackByteBuffer.slice(position, maxHeaderBlock)));
                position += maxHeaderBlock;
                totalLength += Frame.HEADER_LENGTH + maxHeaderBlock;
            }
            hpackByteBuffer.position(position);

            header = generateHeader(FrameType.CONTINUATION, hpack.remaining(), Flags.END_HEADERS, streamId);
            BufferUtil.flipToFlush(header.getByteBuffer(), 0);
            accumulator.append(header);
            accumulator.append(hpack);
            totalLength += Frame.HEADER_LENGTH + hpack.remaining();

            return totalLength;
        }
        else
        {
            flags |= Flags.END_HEADERS;

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
