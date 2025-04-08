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
        if (endStream)
            flags |= Flags.END_STREAM;

        // TODO Look for a way of not allocating a large buffer here.
        //      Possibly the hpack encoder could be changed to take the accumulator, but that is a lot of changes.
        //      Alternately, we could ensure the accumulator has maxFrameSize space
        //      So long as the buffer is not sliced into continuations, it at least should be available to aggregate
        //      subsequent frames into... but likely only a frame header followed by an accumulated data frame.
        //      It might also be good to be able to split the table into continuation frames as it is generated?
        RetainableByteBuffer hpack = encode(encoder, metaData);
        BufferUtil.flipToFlush(hpack.getByteBuffer(), 0);
        int hpackLength = hpack.remaining();

        int maxHeaderBlock = getMaxFrameSize();
        if (maxHeaderBlockFragment > 0)
            maxHeaderBlock = Math.min(maxHeaderBlock, maxHeaderBlockFragment);

        // Split into CONTINUATION frames if necessary.
        if (hpackLength > maxHeaderBlock)
        {
            int start = accumulator.remaining();

            int length = maxHeaderBlock + (priority == null ? 0 : PriorityFrame.PRIORITY_LENGTH);

            // Generate HEADERS frame with possible PRIORITY frame.
            generateHeader(accumulator, FrameType.HEADERS, length, flags, streamId);
            generatePriority(accumulator, priority);
            accumulator.add(hpack.slice(maxHeaderBlock));
            hpack.skip(maxHeaderBlock);

            // Generate CONTINUATION frames that are not the last.
            while (hpack.remaining() > maxHeaderBlock)
            {
                generateHeader(accumulator, FrameType.CONTINUATION, maxHeaderBlock, Flags.NONE, streamId);
                accumulator.add(hpack.slice(maxHeaderBlock));
                hpack.skip(maxHeaderBlock);
            }

            // Generate the last CONTINUATION frame.
            generateHeader(accumulator, FrameType.CONTINUATION, hpack.remaining(), Flags.END_HEADERS, streamId);
            accumulator.add(hpack);

            return accumulator.remaining() - start;
        }
        else
        {
            flags |= Flags.END_HEADERS;

            int length = hpackLength + (priority == null ? 0 : PriorityFrame.PRIORITY_LENGTH);
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
