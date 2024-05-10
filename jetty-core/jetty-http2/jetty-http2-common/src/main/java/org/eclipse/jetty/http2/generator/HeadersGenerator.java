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
    public int generate(RetainableByteBuffer.Mutable accumulator, Frame frame) throws HpackException
    {
        HeadersFrame headersFrame = (HeadersFrame)frame;
        return generateHeaders(accumulator, headersFrame.getStreamId(), headersFrame.getMetaData(), headersFrame.getPriority(), headersFrame.isEndStream());
    }

    public int generateHeaders(RetainableByteBuffer.Mutable accumulator, int streamId, MetaData metaData, PriorityFrame priority, boolean endStream) throws HpackException
    {
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);

        int flags = Flags.NONE;

        if (priority != null)
            flags = Flags.PRIORITY;

        RetainableByteBuffer hpack = encode(encoder, metaData, getMaxFrameSize());
        ByteBuffer hpackByteBuffer = hpack.getByteBuffer();
        BufferUtil.flipToFlush(hpackByteBuffer, 0);
        int hpackLength = hpackByteBuffer.remaining();

        // Split into CONTINUATION frames if necessary.
        if (maxHeaderBlockFragment > 0 && hpackLength > maxHeaderBlockFragment)
        {
            if (endStream)
                flags |= Flags.END_STREAM;

            int length = maxHeaderBlockFragment;
            if (priority != null)
                length += PriorityFrame.PRIORITY_LENGTH;

            generateHeader(accumulator, FrameType.HEADERS, length, flags, streamId);
            generatePriority(accumulator, priority);
            hpackByteBuffer.limit(maxHeaderBlockFragment);
            accumulator.add(RetainableByteBuffer.wrap(hpackByteBuffer.slice()));

            int totalLength = Frame.HEADER_LENGTH + length;

            int position = maxHeaderBlockFragment;
            int limit = position + maxHeaderBlockFragment;
            while (limit < hpackLength)
            {
                hpackByteBuffer.position(position).limit(limit);
                generateHeader(accumulator, FrameType.CONTINUATION, maxHeaderBlockFragment, Flags.NONE, streamId);
                accumulator.append(RetainableByteBuffer.wrap(hpackByteBuffer.slice()));
                position += maxHeaderBlockFragment;
                limit += maxHeaderBlockFragment;
                totalLength += Frame.HEADER_LENGTH + maxHeaderBlockFragment;
            }

            hpackByteBuffer.position(position).limit(hpackLength);
            generateHeader(accumulator, FrameType.CONTINUATION, hpack.remaining(), Flags.END_HEADERS, streamId);
            accumulator.add(hpack);
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

            generateHeader(accumulator, FrameType.HEADERS, length, flags, streamId);
            generatePriority(accumulator, priority);
            accumulator.add(hpack);

            return Frame.HEADER_LENGTH + length;
        }
    }

    private void generatePriority(RetainableByteBuffer.Mutable buffer, PriorityFrame priority)
    {
        if (priority != null)
        {
            priorityGenerator.generatePriorityBody(buffer, priority.getStreamId(),
                priority.getParentStreamId(), priority.getWeight(), priority.isExclusive());
        }
    }
}
