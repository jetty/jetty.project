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

import org.eclipse.jetty.http3.frames.Frame;
import org.eclipse.jetty.http3.frames.FrameType;
import org.eclipse.jetty.http3.frames.HeadersFrame;
import org.eclipse.jetty.http3.internal.VarLenInt;
import org.eclipse.jetty.http3.qpack.QpackEncoder;
import org.eclipse.jetty.http3.qpack.QpackException;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.BufferUtil;

public class HeadersGenerator extends FrameGenerator
{
    private final QpackEncoder encoder;
    private final int maxLength;
    private final boolean useDirectByteBuffers;

    public HeadersGenerator(ByteBufferPool bufferPool, QpackEncoder encoder, int maxLength, boolean useDirectByteBuffers)
    {
        super(bufferPool);
        this.encoder = encoder;
        this.maxLength = maxLength;
        this.useDirectByteBuffers = useDirectByteBuffers;
    }

    @Override
    public int generate(ByteBufferPool.Accumulator accumulator, long streamId, Frame frame, Consumer<Throwable> fail)
    {
        HeadersFrame headersFrame = (HeadersFrame)frame;
        return generateHeadersFrame(accumulator, streamId, headersFrame, fail);
    }

    private int generateHeadersFrame(ByteBufferPool.Accumulator accumulator, long streamId, HeadersFrame frame, Consumer<Throwable> fail)
    {
        try
        {
            // Reserve initial bytes for the frame header bytes.
            int frameTypeLength = VarLenInt.length(FrameType.HEADERS.type());
            int maxHeaderLength = frameTypeLength + VarLenInt.MAX_LENGTH;
            // The capacity of the buffer is larger than maxLength, but we need to enforce at most maxLength.
            RetainableByteBuffer buffer = getByteBufferPool().acquire(maxHeaderLength + maxLength, useDirectByteBuffers);
            ByteBuffer byteBuffer = buffer.getByteBuffer();
            BufferUtil.clearToFill(byteBuffer);
            byteBuffer.position(maxHeaderLength);
            byteBuffer.limit(byteBuffer.position() + maxLength);
            // Encode after the maxHeaderLength.
            encoder.encode(byteBuffer, streamId, frame.getMetaData());
            byteBuffer.flip();
            byteBuffer.position(maxHeaderLength);
            int dataLength = buffer.remaining();
            int headerLength = frameTypeLength + VarLenInt.length(dataLength);
            int position = byteBuffer.position() - headerLength;
            byteBuffer.position(position);
            VarLenInt.encode(byteBuffer, FrameType.HEADERS.type());
            VarLenInt.encode(byteBuffer, dataLength);
            byteBuffer.position(position);
            accumulator.append(buffer);
            return headerLength + dataLength;
        }
        catch (QpackException x)
        {
            if (fail != null)
                fail.accept(x);
            return -1;
        }
    }
}
