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

import org.eclipse.jetty.http2.Flags;
import org.eclipse.jetty.http2.frames.DataFrame;
import org.eclipse.jetty.http2.frames.Frame;
import org.eclipse.jetty.http2.frames.FrameType;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.Callback;

public class DataGenerator
{
    private final HeaderGenerator headerGenerator;

    public DataGenerator(HeaderGenerator headerGenerator)
    {
        this.headerGenerator = headerGenerator;
    }

    public int generate(RetainableByteBuffer.Mutable accumulator, DataFrame frame, int maxLength)
    {
        int streamId = frame.getStreamId();
        if (streamId < 0)
            throw new IllegalArgumentException("Invalid stream id: " + streamId);

        ByteBuffer byteBuffer = frame.getByteBuffer();
        if (byteBuffer == Content.Sink.CONTENT_SOURCE)
            return generateData(accumulator, streamId, frame.getContentSource(), frame.isEndStream(), maxLength);
        else
            return generateData(accumulator, streamId, byteBuffer, frame.isEndStream(), maxLength);
    }

    public int generateData(RetainableByteBuffer.Mutable accumulator, int streamId, ByteBuffer data, boolean last, int maxLength)
    {
        int dataLength = data.remaining();
        int maxFrameSize = headerGenerator.getMaxFrameSize();
        int length = Math.min(dataLength, Math.min(maxFrameSize, maxLength));
        if (length == dataLength)
        {
            generateFrame(accumulator, streamId, data, last);
        }
        else
        {
            int position = data.position();
            ByteBuffer slice = data.slice(position, length);
            data.position(position + length);
            generateFrame(accumulator, streamId, slice, false);
        }
        return Frame.HEADER_LENGTH + length;
    }

    private void generateFrame(RetainableByteBuffer.Mutable accumulator, int streamId, ByteBuffer data, boolean last)
    {
        int length = data.remaining();

        int flags = Flags.NONE;
        if (last)
            flags |= Flags.END_STREAM;

        headerGenerator.generate(accumulator, FrameType.DATA, Frame.HEADER_LENGTH + length, length, flags, streamId);
        // Skip empty data buffers.
        if (data.remaining() > 0)
            accumulator.add(data);
    }

    private int generateData(RetainableByteBuffer.Mutable accumulator, int streamId, Content.Source.Seekable source, boolean last, int maxLength)
    {
        long dataLength = source.remaining();
        int maxFrameSize = headerGenerator.getMaxFrameSize();
        int length = (int)Math.min(dataLength, Math.min(maxFrameSize, maxLength));

        last = last && length == dataLength;

        int flags = Flags.NONE;
        if (last)
            flags |= Flags.END_STREAM;

        headerGenerator.generate(accumulator, FrameType.DATA, Frame.HEADER_LENGTH + length, length, flags, streamId);
        Content.Source.Seekable slice = source.slice(source.position(), length);
        source.position(source.position() + length);
        accumulator.add(new ContentSourceRetainableByteBuffer(slice));

        return Frame.HEADER_LENGTH + length;
    }

    private static class ContentSourceRetainableByteBuffer implements RetainableByteBuffer
    {
        private final Content.Source.Seekable source;

        private ContentSourceRetainableByteBuffer(Content.Source.Seekable source)
        {
            this.source = source;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return Content.Sink.CONTENT_SOURCE;
        }

        @Override
        public long size()
        {
            return source.remaining();
        }

        @Override
        public int remaining()
        {
            return Math.toIntExact(size());
        }

        @Override
        public void writeTo(Content.Sink sink, boolean last, Callback callback)
        {
            // The "last" parameter is not used here, since "last-ness" has
            // already been encoded by the generator in DATA frame header bytes.
            Content.transfer(source, false, sink, callback);
        }
    }
}
