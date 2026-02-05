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

package org.eclipse.jetty.http3.generator;

import java.nio.ByteBuffer;
import java.util.function.Consumer;

import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.FrameType;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.quic.util.VarLenInt;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;

public class DataGenerator extends FrameGenerator
{
    private final boolean useDirectByteBuffers;

    public DataGenerator(ByteBufferPool bufferPool, boolean useDirectByteBuffers)
    {
        super(bufferPool);
        this.useDirectByteBuffers = useDirectByteBuffers;
    }

    @Override
    public long generate(RetainableByteBuffer.Mutable accumulator, long streamId, Frame frame, Consumer<Throwable> fail)
    {
        DataFrame dataFrame = (DataFrame)frame;
        ByteBuffer byteBuffer = dataFrame.getByteBuffer();
        if (byteBuffer == Content.Sink.CONTENT_SOURCE)
            return generateDataFrame(accumulator, dataFrame.getContentSource());
        else
            return generateDataFrame(accumulator, byteBuffer);
    }

    private long generateDataFrame(RetainableByteBuffer.Mutable accumulator, ByteBuffer data)
    {
        long dataLength = data.remaining();
        int headerLength = generateHeader(accumulator, dataLength);
        accumulator.add(RetainableByteBuffer.wrap(data));
        return headerLength + dataLength;
    }

    private long generateDataFrame(RetainableByteBuffer.Mutable accumulator, Content.Source.Seekable contentSource)
    {
        long dataLength = contentSource.getLength();
        int headerLength = generateHeader(accumulator, dataLength);
        accumulator.add(new ContentSourceRetainableByteBuffer(contentSource));
        return headerLength + dataLength;
    }

    private int generateHeader(RetainableByteBuffer.Mutable accumulator, long dataLength)
    {
        int headerLength = VarLenInt.length(FrameType.DATA.type()) + VarLenInt.length(dataLength);
        RetainableByteBuffer header = getByteBufferPool().acquire(headerLength, useDirectByteBuffers);
        ByteBuffer byteBuffer = header.getByteBuffer();
        BufferUtil.clearToFill(byteBuffer);
        VarLenInt.encode(byteBuffer, FrameType.DATA.type());
        VarLenInt.encode(byteBuffer, dataLength);
        byteBuffer.flip();
        accumulator.add(header);
        return headerLength;
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
            Content.transfer(source, last, sink, callback);
        }
    }
}
