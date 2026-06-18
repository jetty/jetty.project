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

import java.util.List;

import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.http2.frames.HeadersFrame;
import org.eclipse.jetty.http2.frames.PriorityFrame;
import org.eclipse.jetty.http2.hpack.HpackEncoder;
import org.eclipse.jetty.http2.hpack.HpackException;
import org.eclipse.jetty.util.buffer.ReadableBuffer;
import org.eclipse.jetty.util.buffer.WritableBuffer;

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
    public int generate(List<ReadableBuffer> accumulator, Frame frame) throws HpackException
    {
        HeadersFrame headersFrame = (HeadersFrame)frame;
        return generateHeaders(accumulator, headersFrame.getStreamId(), headersFrame.getMetaData(), headersFrame.getPriority(), headersFrame.isEndStream());
    }

    public int generateHeaders(List<ReadableBuffer> accumulator, int streamId, MetaData metaData, PriorityFrame priority, boolean endStream) throws HpackException
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
        ReadableBuffer hpack = encode(encoder, metaData);

        // The hpack encoder can never generate a buffer larger than 2 GB, so it is safe to cast remaining() to int.
        int hpackLength = (int)hpack.remaining();

        int maxHeaderBlock = getMaxFrameSize();
        if (maxHeaderBlockFragment > 0)
            maxHeaderBlock = Math.min(maxHeaderBlock, maxHeaderBlockFragment);

        // Split into CONTINUATION frames if necessary.
        if (hpackLength > maxHeaderBlock)
        {
            long start = accumulator.stream().mapToLong(ReadableBuffer::remaining).sum();

            int length = maxHeaderBlock + (priority == null ? 0 : PriorityFrame.PRIORITY_LENGTH);

            // Generate HEADERS frame with possible PRIORITY frame.
            WritableBuffer wb = generateHeader(FrameType.HEADERS, length, flags, streamId);
            generatePriority(wb, priority);
            accumulator.add(wb.toReadable());
            ReadableBuffer slice = hpack.slice(hpack.position(), maxHeaderBlock);
            accumulator.add(slice);
            hpack.position(hpack.position() + maxHeaderBlock);

            // Generate CONTINUATION frames that are not the last.
            while (hpack.remaining() > maxHeaderBlock)
            {
                accumulator.add(generateHeader(FrameType.CONTINUATION, maxHeaderBlock, Flags.NONE, streamId).toReadable());
                accumulator.add(hpack.slice(hpack.position(), maxHeaderBlock));
                hpack.position(hpack.position() + maxHeaderBlock);
            }

            // Generate the last CONTINUATION frame.
            // The hpack buffer can never be > Integer.MAX_VALUE so casting remaining() to int is safe.
            accumulator.add(generateHeader(FrameType.CONTINUATION, (int)hpack.remaining(), Flags.END_HEADERS, streamId).toReadable());
            accumulator.add(hpack);

            // TODO overflow?
            return Math.toIntExact(accumulator.stream().mapToLong(ReadableBuffer::remaining).sum() - start);
        }
        else
        {
            flags |= Flags.END_HEADERS;

            int length = hpackLength + (priority == null ? 0 : PriorityFrame.PRIORITY_LENGTH);
            WritableBuffer wb = generateHeader(FrameType.HEADERS, length, flags, streamId);
            generatePriority(wb, priority);
            accumulator.add(wb.toReadable());
            accumulator.add(hpack);

            return Frame.HEADER_LENGTH + length;
        }
    }

    private void generatePriority(WritableBuffer accumulator, PriorityFrame priority)
    {
        if (priority != null)
        {
            priorityGenerator.generatePriorityBody(accumulator, priority.getStreamId(),
                priority.getParentStreamId(), priority.getWeight(), priority.isExclusive());
        }
    }
}
